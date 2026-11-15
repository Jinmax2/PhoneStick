package mingww64.phonestick

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** 镜像里的一个目录项 */
data class FsEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    /** 修改时间（秒），0 表示未知（部分文件系统不提供） */
    val mtime: Long
)

/**
 * 镜像文件系统访问的统一接口。
 *
 * 两种实现：
 *   - [MountedImageFs]：内核 loop 挂载，读写都走内核，功能最完整；
 *   - [ReaderImageFs]：内核没有对应驱动时，用内置用户态解析器（只读）。
 * UI 只依赖这个接口，因此"只读降级"完全是透明的。
 *
 * 路径约定：内部路径一律以 "/" 开头、用 "/" 分隔（"/" 表示镜像根目录），
 * 与宿主机的绝对路径无关，避免调用方拼错。
 */
interface ImageFs : Closeable {

    /** 文件系统显示名，如 "FAT32" / "NTFS" */
    val displayName: String

    /** 是否只读（只读时 UI 必须禁用增删改） */
    val readOnly: Boolean

    /** 实现方式描述，用于"镜像信息"对话框，如 "内核 loop 挂载 · /dev/block/loop7" */
    val detail: String

    /** (总容量, 可用容量)，拿不到返回 null */
    fun stats(): Pair<Long, Long>?

    /** 列目录，不保证排序 */
    fun list(rel: String): List<FsEntry>

    /** 把 input 写成 relDir/name（覆盖同名文件） */
    fun importFile(relDir: String, name: String, input: InputStream)

    /** 把 rel 读出到 out，返回字节数 */
    fun exportFile(rel: String, out: OutputStream): Long

    /** 删除文件或目录（目录递归删除） */
    fun delete(rel: String)

    /** 在 relDir 下新建目录 */
    fun mkdir(relDir: String, name: String)

    /** 重命名（同一目录内） */
    fun rename(rel: String, newName: String)

    /** 允许重复 close */
    override fun close()

    companion object {
        /** 拼接内部路径：child("/a", "b.txt") == "/a/b.txt" */
        fun child(dir: String, name: String): String {
            val d = normalize(dir)
            return if (d == "/") "/$name" else "$d/$name"
        }

        /** 规范化：去掉多余斜杠，保证以 "/" 开头；"/" 表示根 */
        fun normalize(path: String): String {
            val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty()) return "/"
            return "/" + parts.joinToString("/")
        }

        /** 上一级目录；根目录的上一级仍是根 */
        fun parent(path: String): String {
            val n = normalize(path)
            if (n == "/") return "/"
            val idx = n.lastIndexOf('/')
            return if (idx <= 0) "/" else n.substring(0, idx)
        }

        /** 路径最后一段（文件名） */
        fun nameOf(path: String): String {
            val n = normalize(path)
            return if (n == "/") "/" else n.substring(n.lastIndexOf('/') + 1)
        }
    }
}
