package mingww64.phonestick.fs

/**
 * 镜像里可能出现的文件系统类型，以及与内核挂载相关的一切信息。
 *
 * 挂载类型候选按"最可能成功"排序：
 *   - exFAT：AOSP 5.4+ 用 exfat，三星/vendor 内核多半是 sdfat
 *   - NTFS：ntfs3（5.15+）、ntfs（老内核只读驱动）、ufsd（Paragon 商业驱动，部分国产 ROM）
 *   - ext4：ext4 驱动同时能挂 ext2/ext3，所以不需要额外候选
 */
enum class FsKind(
    val label: String,
    val mountTypes: List<String>,
    /** 挂载附加选项（不含 rw/ro，由调用方拼） */
    val extraOpts: String,
    /** 该类型是否可能可写；ISO 天然只读 */
    val maybeWritable: Boolean,
    /** 内置用户态解析器是否支持（见 fs/reader） */
    val hasBuiltinReader: Boolean
) {
    FAT32("FAT32", listOf("vfat"), "iocharset=utf8,umask=0000,shortname=mixed", true, true),
    FAT16("FAT", listOf("vfat"), "iocharset=utf8,umask=0000,shortname=mixed", true, true),
    EXFAT("exFAT", listOf("exfat", "sdfat"), "iocharset=utf8,umask=0000", true, true),
    NTFS("NTFS", listOf("ntfs3", "ntfs", "ufsd"), "iocharset=utf8,umask=0000", true, true),
    EXT4("ext4", listOf("ext4", "ext2"), "", true, true),
    ISO9660("ISO9660", listOf("iso9660"), "iocharset=utf8", false, false),
    F2FS("F2FS", listOf("f2fs"), "", true, false),
    UNKNOWN("未知", emptyList(), "", false, false);

    companion object {
        /**
         * 按引导扇区特征识别文件系统。
         * 与 native/pstrim.c 的 detect_fs 保持一致的判断顺序（用同一套魔数，
         * 避免"能回收但不能浏览"或者反过来的错位）。
         */
        fun detect(io: BlockIo, partOffset: Long): FsKind {
            if (partOffset < 0 || partOffset + 512 > io.size) return UNKNOWN
            val bs = io.readAt(partOffset, 512)
            if (bs.size >= 512) {
                if (Bytes.matches(bs, 3, "EXFAT   ")) return EXFAT
                if (Bytes.matches(bs, 3, "NTFS    ")) return NTFS
                if (Bytes.matches(bs, 82, "FAT32")) return FAT32
                if (Bytes.matches(bs, 54, "FAT12")) return FAT16
                if (Bytes.matches(bs, 54, "FAT16")) return FAT16
                if (Bytes.matches(bs, 3, "MSWIN4.1") && Bytes.u8(bs, 16) > 0 &&
                    Bytes.le16(bs, 11) == 512 && Bytes.le16(bs, 17) > 0
                ) {
                    // 没有 FAT12/16/32 字样但结构像 FAT（少数工具会留空标签）
                    return FAT16
                }
            }
            // ext2/3/4：超级块固定位于分区内偏移 1024，魔数 0xEF53 在 +0x38
            if (partOffset + 1024 + 0x38 + 2 <= io.size) {
                val m = io.readAt(partOffset + 1024 + 0x38, 2)
                if (m.size == 2 && Bytes.le16(m, 0) == 0xEF53) return EXT4
            }
            // ISO9660：第 16 个 2048 扇区（偏移 32768）里第 2 字节起是 "CD001"
            if (partOffset + 32768 + 2048 <= io.size) {
                val iso = io.readAt(partOffset + 32768, 8)
                if (iso.size >= 6 && Bytes.matches(iso, 1, "CD001")) return ISO9660
            }
            return UNKNOWN
        }
    }
}
