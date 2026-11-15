package mingww64.phonestick.fs

import mingww64.phonestick.FsEntry

/**
 * 解析 `stat -c '%f\t%s\t%Y\t%n'` 的一行输出。
 *
 * 为什么要单独抽出来：不同实现对这个格式串的处理不一样 ——
 *   - toybox（Android）会把 \t 解释成真正的制表符；
 *   - GNU coreutils 的 -c 不解释转义，原样输出反斜杠 + t。
 * 两种都得认，否则真机上会出现"列出目录但名字里带着 \t"的现象。
 *
 * 分隔符只在「模式字段之后」匹配，因此文件名本身含 TAB 或 \t 都不会被误切。
 */
object StatLine {

    data class Raw(val mode: Long, val size: Long, val mtime: Long, val name: String)

    fun parse(raw: String): Raw? {
        if (raw.isBlank()) return null
        val sep = when {
            MODE_THEN_LITERAL_TAB.containsMatchIn(raw) -> "\\t"
            MODE_THEN_TAB.containsMatchIn(raw) -> "\t"
            else -> return null
        }
        val parts = raw.split(sep, limit = 4)
        if (parts.size != 4) return null
        val mode = parts[0].trim().toLongOrNull(16) ?: return null
        val size = parts[1].trim().toLongOrNull() ?: return null
        val mtime = parts[2].trim().toLongOrNull() ?: return null
        var name = parts[3]
        if (name.startsWith("./")) name = name.substring(2)
        if (name.isEmpty() || name == "." || name == "..") return null
        return Raw(mode, size, mtime, name)
    }

    /** 转成对外的目录项：mode 的 S_IFMT 段为 0x4000 即目录 */
    fun toEntry(raw: String): FsEntry? {
        val r = parse(raw) ?: return null
        return FsEntry(r.name, isDir = (r.mode and 0xF000L) == 0x4000L, size = r.size, mtime = r.mtime)
    }

    private val MODE_THEN_LITERAL_TAB = Regex("^[0-9a-fA-F]{1,8}\\\\t")
    private val MODE_THEN_TAB = Regex("^[0-9a-fA-F]{1,8}\t")
}
