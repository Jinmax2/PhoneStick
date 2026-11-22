package mingww64.phonestick.fs

import mingww64.phonestick.FsEntry
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/**
 * 内核没有对应文件系统驱动时的用户态"只读卷"。
 * 只提供「看目录」和「读文件」，写操作一律不支持 —— 用户态写 NTFS/ext4
 * 的代价和风险都远超收益（元数据一错就是整个镜像报废）。
 */
interface ReadOnlyVolume : Closeable {
    /** 展示名，如 "NTFS" */
    val label: String

    /** 实现说明，如 "内置 NTFS 解析器（只读）" */
    val detail: String

    fun list(path: String): List<FsEntry>

    /** 把 path 的内容写到 out，返回字节数 */
    fun copyFile(path: String, out: OutputStream): Long
}

/**
 * NTFS 只读解析器（用户态）。
 *
 * 为什么需要它：Android 4.x/5.x 时代的内核（本例是 4.14）没有 ntfs3 驱动，
 * 老的 ntfs 驱动不仅只读、而且很多 ROM 根本没编译进去。镜像来自 Windows 时
 * 常见的就是 NTFS，没有这层就只能提示"不支持"。
 *
 * 实现的读取路径（够用就好，不追求完整）：
 *   - 引导扇区 → 簇大小 / MFT 位置 / 记录大小
 *   - MFT 记录：应用 update sequence 修正（fixup），遍历属性
 *   - $FILE_NAME：路径解析、列表里的名字/大小/时间
 *   - $INDEX_ROOT + $INDEX_ALLOCATION：目录枚举（B+ 树叶节点与索引节点都算，
 *     NTFS 的索引节点本身就是真实文件，按记录号去重即可）
 *   - $DATA：驻留直接读，非驻留按 runlist 读（稀疏段补零）
 *
 * 明确不支持：压缩文件（$DATA 标志位 0x0001）、加密文件、备用数据流。
 * 遇到时抛 IOException 并给出可读的原因。
 */
class NtfsReader(
    private val io: BlockIo,
    private val partOffset: Long
) : ReadOnlyVolume {

    override val label: String = "NTFS"
    override val detail: String = "内置 NTFS 解析器（只读）"

    private val bytesPerSector: Int
    private val clusterSize: Int
    private val recordSize: Int
    private val mftPhysOffset: Long
    private val totalClusters: Long

    /** MFT 自身的 runlist（MFT 长时间使用后会碎片化，必须按 runlist 定位记录） */
    private val mftRuns: List<Run> by lazy { loadMftRuns() }

    private var indexBlockSize: Int = 4096

    init {
        val bs = io.readAt(partOffset, 512)
        if (bs.size < 512 || !Bytes.matches(bs, 3, "NTFS    ")) {
            throw IOException("不是 NTFS 卷")
        }
        bytesPerSector = Bytes.le16(bs, 0x0B)
        val spc = Bytes.u8(bs, 0x0D)
        if (bytesPerSector !in 512..4096 || spc == 0 || spc > 128) {
            throw IOException("NTFS 引导扇区参数异常")
        }
        clusterSize = bytesPerSector * spc
        val totalSectors = Bytes.le64(bs, 0x28)
        totalClusters = if (totalSectors > 0) totalSectors / spc else 0
        val mftLcn = Bytes.le64(bs, 0x30)
        val cpr = bs[0x40].toInt() // 有符号：负数表示 2^(-n) 字节
        recordSize = if (cpr < 0) 1 shl (-cpr) else cpr * clusterSize
        if (recordSize !in 256..65536) throw IOException("NTFS MFT 记录大小异常：$recordSize")
        if (mftLcn <= 0) throw IOException("NTFS MFT 位置异常")
        mftPhysOffset = partOffset + mftLcn * clusterSize
        if (mftPhysOffset >= io.size) throw IOException("NTFS MFT 超出镜像范围")
    }

    // ---------------- runlist ----------------

    private data class Run(val vcn: Long, val lcn: Long, val count: Long) {
        /** lcn == 0 表示稀疏段（逻辑上全是 0，不占物理扇区） */
        val sparse: Boolean get() = lcn == 0L
    }

    private fun parseRuns(buf: ByteArray, start: Int, end: Int): List<Run> {
        val runs = ArrayList<Run>()
        var p = start
        var lcn = 0L
        var vcn = 0L
        val limit = minOf(end, buf.size)
        while (p < limit) {
            val h = Bytes.u8(buf, p)
            p++
            if (h == 0) break
            val lenBytes = h and 0x0F
            val offBytes = h shr 4
            if (lenBytes == 0 || lenBytes > 8 || offBytes > 8) break
            if (p + lenBytes + offBytes > limit) break
            var count = 0L
            for (i in 0 until lenBytes) count = count or (Bytes.u8(buf, p + i).toLong() shl (8 * i))
            p += lenBytes
            var delta = 0L
            if (offBytes > 0) {
                var v = 0L
                for (i in 0 until offBytes) v = v or (Bytes.u8(buf, p + i).toLong() shl (8 * i))
                // 符号扩展
                if (offBytes < 8 && (v and (1L shl (offBytes * 8 - 1))) != 0L) {
                    v = v or (-1L shl (offBytes * 8))
                }
                delta = v
                p += offBytes
            }
            if (count > 0) {
                if (offBytes == 0) {
                    runs.add(Run(vcn, 0L, count))
                } else {
                    lcn += delta
                    runs.add(Run(vcn, lcn, count))
                }
                vcn += count
            }
        }
        return runs
    }

    /** 从 runlist 里按逻辑偏移读一段（稀疏段补零） */
    private fun readAtRuns(runs: List<Run>, off: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        if (len <= 0) return out
        var done = 0
        var pos = off
        for (r in runs) {
            val runBytes = r.count * clusterSize
            val runStart = r.vcn * clusterSize
            val runEnd = runStart + runBytes
            if (pos >= runEnd) continue
            if (done >= len) break
            if (pos < runStart) pos = runStart
            val inRun = pos - runStart
            val avail = minOf(runBytes - inRun, (len - done).toLong()).toInt()
            if (avail <= 0) continue
            if (r.sparse) {
                java.util.Arrays.fill(out, done, done + avail, 0)
            } else {
                io.readFully(partOffset + r.lcn * clusterSize + inRun, out, done, avail)
            }
            done += avail
            pos += avail
        }
        return if (done == len) out else out.copyOf(done)
    }

    private fun streamRuns(runs: List<Run>, size: Long, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var remaining = size
        for (r in runs) {
            if (remaining <= 0) break
            var runRemain = r.count * clusterSize
            var phys = if (r.sparse) 0L else partOffset + r.lcn * clusterSize
            while (runRemain > 0 && remaining > 0) {
                val n = minOf(runRemain, remaining, buf.size.toLong()).toInt()
                if (r.sparse) {
                    java.util.Arrays.fill(buf, 0, n, 0)
                } else {
                    io.readFully(phys, buf, 0, n)
                }
                sink(buf, n)
                runRemain -= n
                remaining -= n
                if (!r.sparse) phys += n
            }
        }
    }

    // ---------------- 记录与属性 ----------------

    private class IndexEntry(val ref: Long, val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    private fun loadMftRuns(): List<Run> {
        val rec = readRecordRaw(mftPhysOffset)
            ?: throw IOException("无法读取 \$MFT 记录")
        val data = rec.firstAttr(0x80L, unnamedOnly = true)
            ?: throw IOException("\$MFT 没有数据属性")
        if (!data.nonResident) throw IOException("\$MFT 数据属性异常（驻留）")
        return parseRuns(rec.buf, data.start + data.runOffset, data.start + data.length)
    }

    private fun readRecordRaw(physOffset: Long): Record? {
        val buf = io.readAt(physOffset, recordSize)
        if (buf.size < recordSize || !Bytes.matches(buf, 0, "FILE")) return null
        if (!applyFixup(buf)) return null
        return Record(buf)
    }

    /** 应用 update sequence 修正：每个扇区末尾两字节换成 USA 里的备份值 */
    private fun applyFixup(buf: ByteArray): Boolean {
        val usaOff = Bytes.le16(buf, 0x04)
        val usaCount = Bytes.le16(buf, 0x06)
        if (usaCount < 1 || usaOff + usaCount * 2 > buf.size) return false
        val usn = buf[usaOff]
        for (i in 1 until usaCount) {
            val end = i * bytesPerSector
            if (end > buf.size || end - 2 < 0) break
            if (buf[end - 2] != usn || buf[end - 1] != buf[usaOff + 1]) {
                // 校验不一致说明记录不完整；不阻断，尽量解析
            }
            buf[end - 2] = buf[usaOff + i * 2]
            buf[end - 1] = buf[usaOff + i * 2 + 1]
        }
        return true
    }

    private fun readRecord(index: Long): Record? {
        val off = index * recordSize
        val runs = mftRuns
        val totalMft = runs.sumOf { it.count } * clusterSize
        if (off < 0 || off >= totalMft) return null
        val buf = readAtRuns(runs, off, recordSize)
        if (buf.size < recordSize || !Bytes.matches(buf, 0, "FILE")) return null
        if (!applyFixup(buf)) return null
        return Record(buf)
    }

    // ---------------- 目录枚举 ----------------

    private fun parseIndexEntries(
        buf: ByteArray,
        start: Int,
        end: Int,
        out: MutableMap<Long, IndexEntry>
    ) {
        var p = start
        val limit = minOf(end, buf.size)
        while (p + 16 <= limit) {
            val ref = Bytes.le64(buf, p)
            val entryLen = Bytes.le16(buf, p + 8)
            val flags = Bytes.le16(buf, p + 12)
            if (entryLen < 16) break
            if (ref != 0L && (flags and 0x02) == 0) {
                // 键就是一条 $FILE_NAME 属性
                val k = p + 16
                if (k + 0x42 <= limit) {
                    val nameLen = Bytes.u8(buf, k + 0x40)
                    val ns = Bytes.u8(buf, k + 0x41)
                    // ns == 2 是 DOS 8.3 短名，跳过避免同一文件出现两次
                    if (nameLen in 1..255 && ns != 2 && k + 0x42 + nameLen * 2 <= limit) {
                        val name = String(buf, k + 0x42, nameLen * 2, Charsets.UTF_16LE)
                        val isDir = (Bytes.le32(buf, k + 0x38) and 0x10000000L) != 0L
                        val realSize = Bytes.le64(buf, k + 0x30)
                        val mtime = filetimeToUnix(Bytes.le64(buf, k + 0x10))
                        val recNo = ref and 0x0000FFFFFFFFFFFFL
                        out.putIfAbsent(recNo, IndexEntry(recNo, name, isDir, realSize, mtime))
                    }
                }
            }
            if ((flags and 0x02) != 0) break
            p += entryLen
        }
    }

    private fun enumDir(rec: Record): List<IndexEntry> {
        val out = LinkedHashMap<Long, IndexEntry>()

        val root = rec.firstAttr(0x90L)
        if (root != null && !root.nonResident && root.valueOffset + 0x20 <= rec.buf.size) {
            val v = root.valueOffset
            indexBlockSize = Bytes.le32(rec.buf, v + 0x08).toInt().coerceIn(512, 64 * 1024)
            val hdr = v + 0x10
            val entriesOff = Bytes.le32(rec.buf, hdr).toInt()
            val entriesLen = Bytes.le32(rec.buf, hdr + 4).toInt()
            val s = hdr + entriesOff
            parseIndexEntries(rec.buf, s, s + entriesLen, out)
        }

        val alloc = rec.firstAttr(0xA0L)
        if (alloc != null && alloc.nonResident) {
            val runs = parseRuns(rec.buf, alloc.start + alloc.runOffset, alloc.start + alloc.length)
            var off = 0L
            val total = alloc.dataSize
            val block = indexBlockSize
            while (off < total) {
                val blk = readAtRuns(runs, off, block)
                if (blk.size < 0x20 || !Bytes.matches(blk, 0, "INDX")) break
                applyFixup(blk)
                val hdr = 0x18
                val entriesOff = Bytes.le32(blk, hdr).toInt()
                val entriesLen = Bytes.le32(blk, hdr + 4).toInt()
                val s = hdr + entriesOff
                if (s >= 0 && s < blk.size) {
                    parseIndexEntries(blk, s, s + entriesLen, out)
                }
                off += block
            }
        }
        return out.values.toList()
    }

    // ---------------- 对外 ----------------

    override fun list(path: String): List<FsEntry> {
        val rec = locate(path) ?: throw IOException("目录不存在：$path")
        if (!rec.isDir) throw IOException("不是目录：$path")
        return enumDir(rec)
            // 跳过 NTFS 元文件（$MFT 之类）和索引里的自引用项（"." / ".."）；
            // mkntfs 建的卷根目录里带一条 "."，不滤掉就会在界面里多出一行"."
            .filter { it.name.isNotEmpty() && !it.name.startsWith("$") && it.name != "." && it.name != ".." }
            .map { FsEntry(it.name, it.isDir, it.size, it.mtime) }
    }

    override fun copyFile(path: String, out: OutputStream): Long {
        val rec = locate(path) ?: throw IOException("文件不存在：$path")
        if (rec.isDir) throw IOException("是目录，不能按文件读取：$path")
        val data = rec.attrs.firstOrNull { it.type == 0x80L && it.nameLen == 0 }
            ?: throw IOException("文件没有默认数据流（可能是系统元文件）")
        if (data.nonResident && (data.flags and 0x0001) != 0) {
            throw IOException("该文件在 NTFS 上是压缩的，内置解析器暂不支持")
        }
        if (!data.nonResident && (data.flags and 0x4000) != 0) {
            throw IOException("该文件是 NTFS 加密文件（EFS），无法读取")
        }

        var written = 0L
        if (data.nonResident) {
            val runs = parseRuns(rec.buf, data.start + data.runOffset, data.start + data.length)
            streamRuns(runs, data.dataSize) { buf, n ->
                out.write(buf, 0, n)
                written += n
            }
        } else {
            val len = minOf(data.valueLen, rec.buf.size - data.valueOffset)
            if (len > 0) out.write(rec.buf, data.valueOffset, len)
            written = len.toLong()
        }
        out.flush()
        return written
    }

    /** 按路径找文件记录（从根记录号 5 开始逐段下钻） */
    private fun locate(path: String): Record? {
        val norm = mingww64.phonestick.ImageFs.normalize(path)
        var current = readRecord(5L) ?: return null
        if (norm == "/") return current
        val parts = norm.split('/').filter { it.isNotEmpty() }
        for ((i, part) in parts.withIndex()) {
            if (!current.isDir) return null
            val hit = enumDir(current).firstOrNull { it.name.equals(part, ignoreCase = true) }
                ?: return null
            val next = readRecord(hit.ref) ?: return null
            current = next
            if (i == parts.size - 1) return current
        }
        return current
    }

    override fun close() {
        runCatching { io.close() }
    }

    private fun filetimeToUnix(ft: Long): Long {
        if (ft <= 0) return 0L
        val sec = ft / 10_000_000L - 11_644_473_600L
        return if (sec > 0) sec else 0L
    }
}

// ---------------- 记录 / 属性的解析（与卷无关，放在文件级便于测试） ----------------

private class Attr(
    val type: Long,
    val nonResident: Boolean,
    /** 属性标志：0x0001 压缩，0x4000 加密，0x8000 稀疏 */
    val flags: Int,
    val nameLen: Int,
    val valueOffset: Int,
    val valueLen: Int,
    val runOffset: Int,
    val dataSize: Long,
    val start: Int,
    val length: Int
)

private class Record(val buf: ByteArray) {
    val isDir: Boolean get() = (Bytes.le16(buf, 0x16) and 0x02) != 0
    val attrs: List<Attr> by lazy { parseAttrs(buf) }

    fun firstAttr(type: Long, unnamedOnly: Boolean = false): Attr? =
        attrs.firstOrNull { it.type == type && (!unnamedOnly || it.nameLen == 0) }
}

/** NTFS 属性列表：type(4) len(4) ... 依次排列，0xFFFFFFFF 结束 */
private fun parseAttrs(buf: ByteArray): List<Attr> {
    val out = ArrayList<Attr>()
    var off = Bytes.le16(buf, 0x14)
    if (off <= 0 || off >= buf.size) return out
    while (off + 16 <= buf.size) {
        val type = Bytes.le32(buf, off)
        if (type == 0xFFFFFFFFL) break
        val len = Bytes.le32(buf, off + 4).toInt()
        if (len < 16 || off + len > buf.size) break
        val nonResident = Bytes.u8(buf, off + 8) == 1
        val nameLen = Bytes.u8(buf, off + 9)
        val flags = Bytes.le16(buf, off + 0x0C)
        val valueLen = if (nonResident) 0 else Bytes.le32(buf, off + 0x10).toInt()
        // 驻留属性的 value 偏移是「相对属性记录起点」的，必须加上属性起点才是记录内绝对偏移
        val valueOffset = if (nonResident) 0 else off + Bytes.le16(buf, off + 0x14)
        val runOffset = if (nonResident) Bytes.le16(buf, off + 0x20) else 0
        val dataSize = if (nonResident) Bytes.le64(buf, off + 0x30) else valueLen.toLong()
        out.add(
            Attr(
                type = type,
                nonResident = nonResident,
                flags = flags,
                nameLen = nameLen,
                valueOffset = valueOffset,
                valueLen = valueLen,
                runOffset = runOffset,
                dataSize = dataSize,
                start = off,
                length = len
            )
        )
        off += len
    }
    return out
}
