package mingww64.phonestick.fs

/**
 * 镜像的分区布局解析。
 *
 * 支持三种布局（与「释放未使用空间」的 pstrim 保持一致）：
 *   - GPT：保护性 MBR（类型 0xEE）→ 读 GPT 头 → 遍历分区条目
 *   - MBR：最多 4 个主分区（扩展/逻辑分区不展开，与 pstrim 一致）
 *   - 无分区表（超级软盘）：整个文件就是一个数据区
 *
 * 与 pstrim 的差别只有一点：这里额外记录每个分区里的文件系统类型，
 * 因为挂载必须显式指定 -t 与偏移。
 */
object VolumeLayout {

    data class Part(
        val offset: Long,
        val size: Long,
        val fs: FsKind
    )

    data class Layout(
        val scheme: String,
        val parts: List<Part>
    ) {
        val usable: List<Part> get() = parts.filter { it.fs != FsKind.UNKNOWN }
    }

    private const val SECTOR = 512L

    fun parse(io: BlockIo): Layout {
        val size = io.size
        if (size < SECTOR) return Layout("空文件", emptyList())

        var scheme = "无分区表"
        var parts: List<Part> = emptyList()

        val mbr = io.readAt(0, 512)
        val hasMbrSig = mbr.size >= 512 &&
            Bytes.u8(mbr, 510) == 0x55 && Bytes.u8(mbr, 511) == 0xAA

        if (hasMbrSig) {
            var protective = false
            for (i in 0 until 4) {
                if (Bytes.u8(mbr, 446 + i * 16 + 4) == 0xEE) protective = true
            }
            if (protective) {
                val gpt = parseGpt(io, size)
                if (gpt.isNotEmpty()) {
                    scheme = "GPT"
                    parts = gpt
                }
            }
            if (parts.isEmpty()) {
                val mbrParts = parseMbr(mbr, size)
                if (mbrParts.isNotEmpty()) {
                    scheme = "MBR(主分区)"
                    parts = mbrParts
                }
            }
        }

        if (parts.isEmpty()) {
            scheme = "无分区表"
            parts = listOf(Part(0L, size, FsKind.UNKNOWN))
        }

        val filled = parts.map { it.copy(fs = FsKind.detect(io, it.offset)) }
        return Layout(scheme, filled)
    }

    private fun parseMbr(mbr: ByteArray, fileSize: Long): List<Part> {
        val out = mutableListOf<Part>()
        for (i in 0 until 4) {
            val e = 446 + i * 16
            val type = Bytes.u8(mbr, e + 4)
            if (type == 0) continue
            // 0x05/0x0F/0x85 是扩展分区容器，里面的逻辑分区不展开
            if (type == 0x05 || type == 0x0F || type == 0x85) continue
            val startLba = Bytes.le32(mbr, e + 8)
            val secs = Bytes.le32(mbr, e + 12)
            if (startLba <= 0 || secs <= 0) continue
            val off = startLba * SECTOR
            if (off + 1024 > fileSize) continue
            var sz = secs * SECTOR
            if (off + sz > fileSize) sz = fileSize - off
            out.add(Part(off, sz, FsKind.UNKNOWN))
        }
        return out
    }

    private fun parseGpt(io: BlockIo, fileSize: Long): List<Part> {
        val hdr = io.readAt(SECTOR, 512)
        if (hdr.size < 512 || !Bytes.matches(hdr, 0, "EFI PART")) return emptyList()
        val entriesLba = Bytes.le64(hdr, 72)
        val numEntries = Bytes.le32(hdr, 80)
        val entrySize = Bytes.le32(hdr, 84)
        if (entriesLba <= 0 || numEntries <= 0 || entrySize < 128 || entrySize > 4096) {
            return emptyList()
        }
        val out = mutableListOf<Part>()
        val count = minOf(numEntries, 128L).toInt()
        var i = 0
        while (i < count) {
            // 每个 4KB 块读一次，避免每条分区都走一次 root 读
            val chunkEntries = minOf(count - i, (4096 / entrySize).toInt().coerceAtLeast(1))
            val bytes = io.readAt(entriesLba * SECTOR + i * entrySize, (chunkEntries * entrySize).toInt())
            if (bytes.size < entrySize) break
            for (k in 0 until chunkEntries) {
                val o = (k * entrySize).toInt()
                if (o + 128 > bytes.size) break
                var empty = true
                for (b in 0 until 16) {
                    if (bytes[o + b].toInt() != 0) {
                        empty = false
                        break
                    }
                }
                if (empty) continue
                val first = Bytes.le64(bytes, o + 32)
                val last = Bytes.le64(bytes, o + 40)
                if (first <= 0 || last < first) continue
                val off = first * SECTOR
                if (off + 1024 > fileSize) continue
                var sz = (last - first + 1) * SECTOR
                if (off + sz > fileSize) sz = fileSize - off
                out.add(Part(off, sz, FsKind.UNKNOWN))
            }
            i += chunkEntries
        }
        return out
    }
}
