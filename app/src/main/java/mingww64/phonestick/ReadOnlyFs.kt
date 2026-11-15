package mingww64.phonestick

import android.util.Log
import mingww64.phonestick.fs.FsKind
import mingww64.phonestick.fs.NtfsReader
import mingww64.phonestick.fs.ReadOnlyVolume
import mingww64.phonestick.fs.SuBlockIo
import mingww64.phonestick.fs.VolumeLayout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 内核挂载全部失败时的兜底：用内置用户态解析器只读打开镜像。
 *
 * 目前内置 NTFS（4.14 这类老内核几乎都没有 NTFS 写驱动）。
 * FAT32 / exFAT / ext4 在 Android 上一定有内核驱动，走挂载即可，
 * 不必再实现一遍用户态读（省下几百行没人会走到的代码）。
 */
object ReadOnlyFs {

    private const val TAG = "ReadOnlyFs"

    fun open(rawPath: String, layout: VolumeLayout.Layout): ImageFs? {
        for (part in layout.usable) {
            if (!part.fs.hasBuiltinReader) continue
            val io = try {
                SuBlockIo(rawPath)
            } catch (t: Throwable) {
                Log.w(TAG, "open block io failed: ${t.message}")
                continue
            }
            try {
                when (part.fs) {
                    FsKind.NTFS -> return ReaderImageFs(NtfsReader(io, part.offset), part)
                    else -> io.close()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "init reader for ${part.fs.label} failed: ${t.message}")
                runCatching { io.close() }
            }
        }
        return null
    }
}

/** 只读卷的 ImageFs 适配：写操作一律抛 IOException，UI 会先看 readOnly 再决定是否给出入口 */
class ReaderImageFs(
    private val volume: ReadOnlyVolume,
    private val part: VolumeLayout.Part
) : ImageFs {

    override val displayName: String get() = volume.label
    override val readOnly: Boolean = true
    override val detail: String get() = "${volume.detail} · 分区偏移 ${part.offset}"

    override fun stats(): Pair<Long, Long>? = null

    override fun list(rel: String): List<FsEntry> = volume.list(ImageFs.normalize(rel))

    override fun importFile(relDir: String, name: String, input: InputStream) {
        throw IOException("只读模式：内核没有提供该文件系统的写入驱动")
    }

    override fun exportFile(rel: String, out: OutputStream): Long =
        volume.copyFile(ImageFs.normalize(rel), out)

    override fun delete(rel: String) {
        throw IOException("只读模式：无法删除")
    }

    override fun mkdir(relDir: String, name: String) {
        throw IOException("只读模式：无法新建目录")
    }

    override fun rename(rel: String, newName: String) {
        throw IOException("只读模式：无法重命名")
    }

    override fun close() {
        runCatching { volume.close() }
    }
}
