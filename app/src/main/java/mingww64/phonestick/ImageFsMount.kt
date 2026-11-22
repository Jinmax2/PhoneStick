package mingww64.phonestick

import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import mingww64.phonestick.fs.FsKind
import mingww64.phonestick.fs.StatLine
import mingww64.phonestick.fs.SuBlockIo
import mingww64.phonestick.fs.VolumeLayout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把镜像（的某个分区）用内核 loop 设备挂载到本机，得到一个可读写的文件系统视图。
 *
 * 为什么用 loop 挂载而不是自己解析文件系统：
 *   1. 内核里的 FAT/exFAT/ext4 驱动是经过千万台设备验证的，写入语义（簇分配、
 *      目录项、日志）由内核负责，不会因为我们的 bug 把用户的镜像写坏；
 *   2. 只有挂载后才能"添加/删除"，自己实现四种文件系统的写路径不现实；
 *   3. 镜像本身是一个普通文件，losetup 支持按字节偏移挂载，正好对上分区表。
 *
 * 必要条件：
 *   - 镜像没有被 USB gadget（LUN）占用。内核在 LUN 打开着同一个文件时，
 *     对它的 loop 挂载会 EINVAL（本项目早期排查过这一条），所以 UI 必须先卸载 U 盘；
 *   - 有 root；mount/umount/losetup 全部走 su。
 *
 * 内核没有驱动时（典型：4.14 内核挂 NTFS），回落到内置用户态解析器（只读），
 * 见 [ReadOnlyFs]。
 */
object ImageFsMount {

    private const val TAG = "ImageFsMount"

    /** 挂载点根目录：放在 /data/local/tmp 下，root 可写、重启自动清理 */
    private const val BASE = "/data/local/tmp/.psfs"

    private val counter = AtomicInteger(0)

    sealed class OpenResult {
        data class Ok(val fs: ImageFs) : OpenResult()
        data class Err(val message: String, val detail: String = "") : OpenResult()
    }

    /**
     * 打开镜像。必须在 IO 线程调用（内部大量 su 往返）。
     *
     * @param imagePath 用户路径或 /data/media 原始路径都可以
     */
    fun open(imagePath: String): OpenResult {
        val raw = ImageStore.toRawPath(imagePath)
        val info = ImageStore.query(raw)
        if (!info.valid) return OpenResult.Err("镜像不存在或为空")

        // ---------- 1. 探测分区与文件系统 ----------
        val layout = try {
            SuBlockIo(raw).use { VolumeLayout.parse(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed: ${t.message}", t)
            return OpenResult.Err("读取镜像失败：${t.message ?: t.javaClass.simpleName}")
        }

        val usable = layout.usable
        if (usable.isEmpty()) {
            return OpenResult.Err(
                "没有在镜像里找到可识别的文件系统。\n\n" +
                    "分区布局：${layout.scheme}\n" +
                    "支持 FAT32 / exFAT / NTFS / ext4 / ISO9660。\n\n" +
                    "裸盘镜像请先在电脑上分区格式化，或用本 App 的「格式化镜像」。"
            )
        }

        // ---------- 2. 清理上次残留的挂载点 / loop ----------
        cleanupStale()

        // ---------- 3. 逐个分区、逐个驱动名尝试挂载 ----------
        // 多分区镜像（例如启动盘）按容量从大到小试：主数据分区通常是最大的那个，
        // EFI 之类的小分区排在后面。
        val errors = StringBuilder()
        for (part in usable.sortedByDescending { it.size }) {
            for (type in part.fs.mountTypes) {
                // 先试读写，失败再试只读：很多内核的 NTFS 老驱动只支持只读
                for (ro in booleanArrayOf(false, true)) {
                    if (!part.fs.maybeWritable && !ro) continue
                    val r = attemptMount(raw, part, type, ro)
                    if (r.fs != null) {
                        Log.d(TAG, "mounted ${part.fs.label} as $type ro=$ro at ${r.fs.mountPoint}")
                        return OpenResult.Ok(r.fs)
                    }
                    errors.append("${part.fs.label} ($type ${if (ro) "ro" else "rw"}): ${r.error}\n")
                }
            }

            // 再试一次"不带附加选项"的挂载：少数厂商驱动（如 ufsd）不认
            // iocharset/umask 这类通用选项，带上就直接 EINVAL。
            val plain = part.fs.mountTypes.firstOrNull() ?: continue
            for (ro in booleanArrayOf(false, true)) {
                if (!part.fs.maybeWritable && !ro) continue
                val r = attemptMount(raw, part, plain, ro, optsOverride = "")
                if (r.fs != null) {
                    Log.d(TAG, "mounted " + part.fs.label + " without extra opts, ro=" + ro)
                    return OpenResult.Ok(r.fs)
                }
                errors.append(part.fs.label + " (" + plain + " 无附加选项 " + (if (ro) "ro" else "rw") + "): " + r.error + "\n")
            }
        }

        // ---------- 4. 内核完全没有驱动时，用内置解析器（只读） ----------
        val reader = ReadOnlyFs.open(raw, layout)
        if (reader != null) {
            Log.d(TAG, "fallback to builtin reader: ${reader.detail}")
            return OpenResult.Ok(reader)
        }

        val kernelFs = kernelFsList()
        return OpenResult.Err(
            "无法挂载镜像里的文件系统。\n\n" +
                "镜像布局：${layout.scheme}，识别到：${usable.joinToString { it.fs.label }}\n\n" +
                "内核尝试记录：\n${errors.toString().trim()}\n\n" +
                "内核支持的本地文件系统：$kernelFs",
            errors.toString()
        )
    }

    /** 内核实际支持的本地文件系统（挂载失败时给用户看，避免"猜"） */
    private fun kernelFsList(): String {
        val res = Shell.cmd("cat /proc/filesystems 2>/dev/null | tr '\\n' ' '").exec()
        val line = res.out.joinToString(" ").replace("nodev", "").trim()
        return if (line.isEmpty()) "未知" else line.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct().joinToString(", ")
    }

    private data class Attempt(val fs: MountedImageFs?, val error: String)

    /**
     * 一次挂载尝试：losetup（带分区偏移）→ mount → 复查实际挂载选项。
     */
    private fun attemptMount(
        image: String,
        part: VolumeLayout.Part,
        type: String,
        readOnly: Boolean,
        /** 传空串表示不加任何附加挂载选项 */
        optsOverride: String? = null
    ): Attempt {
        val mnt = "$BASE/s${counter.incrementAndGet()}_${System.nanoTime() % 100000L}"
        val imgEsc = esc(image)
        val mntEsc = esc(mnt)

        val extraOpts = optsOverride ?: part.fs.extraOpts
        val opts = StringBuilder(if (readOnly) "ro" else "rw")
        if (extraOpts.isNotEmpty()) opts.append(',').append(extraOpts)

        val script = arrayOf(
            "mkdir -p '$mntEsc' 2>/dev/null",
            // losetup -o 指定分区偏移：这样拿到的是整盘 loop，不需要内核识别分区表，
            // 也就能对付"镜像比分区表声明更大"这类脏镜像
            "L=\$(losetup -f --show -o ${part.offset} '$imgEsc' 2>&1)",
            "case \"\$L\" in",
            "  /dev/*) ;;",
            "  *) echo \"PS_NO_LOOP:\$L\"; rmdir '$mntEsc' 2>/dev/null; exit 0 ;;",
            "esac",
            "E=\$(mount -t $type -o $opts \"\$L\" '$mntEsc' 2>&1)",
            "if [ \$? -eq 0 ]; then",
            "  echo \"PS_OK:\$L\"",
            "else",
            "  echo \"PS_FAIL:\$E\"",
            "  losetup -d \"\$L\" 2>/dev/null",
            "  rmdir '$mntEsc' 2>/dev/null",
            "fi"
        )

        val res = try {
            Shell.cmd(*script).exec()
        } catch (t: Throwable) {
            return Attempt(null, "执行挂载脚本失败：${t.message}")
        }

        val okLine = res.out.firstOrNull { it.startsWith("PS_OK:") }
        if (okLine != null) {
            val loop = okLine.removePrefix("PS_OK:").trim()
            // mount 返回值是 0 不代表拿到可写挂载：ext4 遇到错误会 remount-ro，
            // 有些 ROM 的 vfat 也会强制只读。以 /proc/mounts 的实际选项为准。
            val flagRes = Shell.cmd(
                "awk -v m='$mntEsc' '\$2==m{print \$4}' /proc/mounts 2>/dev/null | head -n1"
            ).exec()
            val flags = flagRes.out.firstOrNull()?.trim().orEmpty()
            val effectiveRo = readOnly || flags.split(',').contains("ro")
            return Attempt(MountedImageFs(loop, mnt, part.fs, effectiveRo), "")
        }

        val fail = res.out.firstOrNull { it.startsWith("PS_FAIL:") }
            ?: res.out.firstOrNull { it.startsWith("PS_NO_LOOP:") }
        val msg = when {
            fail == null -> (res.out + res.err).joinToString(" ").trim().ifEmpty { "未知错误" }
            fail.startsWith("PS_NO_LOOP:") -> "losetup 失败：${fail.removePrefix("PS_NO_LOOP:").trim()}"
            else -> fail.removePrefix("PS_FAIL:").trim().ifEmpty { "mount 失败（无输出）" }
        }
        return Attempt(null, msg)
    }

    /**
     * 清理残留：app 被杀掉时 umount 不会执行，会留下挂载点、loop 设备和 /proc 里的挂载。
     * 这里只清理我们自己管的目录下、指向 /data/media/0/PhoneStick 的 loop，
     * 不碰系统或其他 App 的挂载。
     */
    private fun cleanupStale() {
        try {
            Shell.cmd(
                "for d in $BASE/*; do",
                "  if [ -d \"\$d\" ]; then umount -l \"\$d\" 2>/dev/null; rmdir \"\$d\" 2>/dev/null; fi",
                "done 2>/dev/null",
                "for l in \$(losetup -a 2>/dev/null | grep -F '/data/media/0/PhoneStick/' | cut -d: -f1); do",
                "  losetup -d \"\$l\" 2>/dev/null",
                "done 2>/dev/null",
                "true"
            ).exec()
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupStale failed: ${t.message}")
        }
    }
}

/** shell 单引号转义（ImageFsMount 与 MountedImageFs 共用） */
private fun esc(s: String): String = s.replace("'", "'\\''")

/**
 * 内核 loop 挂载出来的文件系统视图。所有操作都直接作用在挂载点上，
 * 因此写路径由内核文件系统驱动负责 —— 不重写数据、不担心元数据损坏。
 */
class MountedImageFs(
    val loopDevice: String,
    val mountPoint: String,
    val kind: FsKind,
    override val readOnly: Boolean
) : ImageFs {

    @Volatile
    private var closed = false

    override val displayName: String get() = kind.label
    override val detail: String get() = "内核挂载 · $loopDevice → $mountPoint"

    private fun abs(rel: String): String {
        val n = ImageFs.normalize(rel)
        return if (n == "/") mountPoint else mountPoint + n
    }

    private fun ensureWritable() {
        if (readOnly) throw IOException("当前为只读模式，无法修改镜像内容")
        if (closed) throw IOException("文件系统已关闭")
    }

    override fun stats(): Pair<Long, Long>? {
        val res = try {
            Shell.cmd("df -k '${esc(mountPoint)}' 2>/dev/null | tail -n 1").exec()
        } catch (t: Throwable) {
            return null
        }
        val line = res.out.firstOrNull { it.isNotBlank() } ?: return null
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 4) return null
        val total = f[1].toLongOrNull() ?: return null
        val avail = f[3].toLongOrNull() ?: return null
        return total * 1024 to avail * 1024
    }

    override fun list(rel: String): List<FsEntry> {
        val dir = abs(rel)
        // stat 的格式串：%f=模式(hex) %s=大小 %Y=修改时间 %n=名字
        // 这里用字面量 \t 作分隔符：GNU stat 的 -c 不解释转义、toybox 的会，
        // 解析侧两种都认（见 fs/StatLine.kt）。
        val fmt = "%f\\t%s\\t%Y\\t%n"
        val dirEsc = esc(dir)
        val script = arrayOf(
            // 【不要 cd 进目录】libsu 的 shell 是常驻进程，cd 之后 cwd 会一直留在
            // 挂载点里，umount 之后内核仍认为有引用，loop 设备就摘不掉（实测残留）。
            // 所以全部用绝对路径列目录，解析时再剥掉前缀。
            "[ -d '" + dirEsc + "' ] || { echo __PS_CD_FAIL__; exit 0; }",
            // find -exec ... + 会自动分批，目录里几千个文件也不会撑爆参数长度；
            // 老 toybox 不支持 "+" 时回落到 shell 通配（目录项较少时足够）
            "find '" + dirEsc + "' -mindepth 1 -maxdepth 1 -exec stat -c '" + fmt + "' {} + 2>/dev/null || " +
                "stat -c '" + fmt + "' -- '" + dirEsc + "'/.[!.]* '" + dirEsc + "'/..?* '" + dirEsc + "'/* 2>/dev/null || true",
            "echo __PS_END__"
        )
        val res = try {
            Shell.cmd(*script).exec()
        } catch (t: Throwable) {
            throw IOException("列目录失败：${t.message}")
        }
        if (res.out.any { it == "__PS_CD_FAIL__" }) {
            throw IOException("无法进入目录：${ImageFs.normalize(rel)}")
        }
        val prefix = "$dir/"
        val out = ArrayList<FsEntry>()
        for (raw in res.out) {
            if (raw == "__PS_END__") break
            // 绝对路径 → 相对名字（find 与通配两种形式输出的都是绝对路径）
            val entry = StatLine.toEntry(raw.replace(prefix, "")) ?: continue
            out.add(entry)
        }
        return out
    }

    override fun importFile(relDir: String, name: String, input: InputStream) {
        ensureWritable()
        val dest = ImageFs.child(relDir, name)
        val path = abs(dest)
        try {
            SuFileOutputStream.open(path, false).use { out ->
                input.copyTo(out, 128 * 1024)
                out.flush()
            }
        } catch (t: Throwable) {
            throw IOException("写入失败：${t.message}")
        } finally {
            Shell.cmd("sync").exec()
        }
    }

    override fun exportFile(rel: String, out: OutputStream): Long {
        val path = abs(rel)
        return try {
            SuFileInputStream.open(path).use { ins ->
                ins.copyTo(out, 128 * 1024)
            }
        } catch (t: Throwable) {
            throw IOException("读取失败：${t.message}")
        }
    }

    override fun delete(rel: String) {
        ensureWritable()
        if (ImageFs.normalize(rel) == "/") throw IOException("不能删除根目录")
        val r = Shell.cmd("rm -rf -- '${esc(abs(rel))}' 2>&1", "sync").exec()
        if (!r.isSuccess) throw IOException("删除失败：${(r.out + r.err).joinToString(" ").trim()}")
    }

    override fun mkdir(relDir: String, name: String) {
        ensureWritable()
        val r = Shell.cmd("mkdir -- '${esc(abs(ImageFs.child(relDir, name)))}' 2>&1", "sync").exec()
        if (!r.isSuccess) throw IOException("新建文件夹失败：${(r.out + r.err).joinToString(" ").trim()}")
    }

    override fun rename(rel: String, newName: String) {
        ensureWritable()
        val from = abs(rel)
        val to = abs(ImageFs.child(ImageFs.parent(rel), newName))
        val r = Shell.cmd("mv -- '${esc(from)}' '${esc(to)}' 2>&1", "sync").exec()
        if (!r.isSuccess) throw IOException("重命名失败：${(r.out + r.err).joinToString(" ").trim()}")
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // 顺序很讲究：先 umount（lazy 兜底），再摘 loop。
            // umount -l 只是把挂载从命名空间摘掉，内核里最后一个引用要等一小会儿，
            // 立刻 losetup -d 会偶发 EBUSY（实测有残留 loop），所以带一次重试。
            Shell.cmd(
                "umount '${esc(mountPoint)}' 2>/dev/null || umount -l '${esc(mountPoint)}' 2>/dev/null || true",
                "sleep 0.2",
                "losetup -d '$loopDevice' 2>/dev/null || { sleep 0.3; losetup -d '$loopDevice' 2>/dev/null; }",
                "rmdir '${esc(mountPoint)}' 2>/dev/null",
                "true"
            ).exec()
        } catch (t: Throwable) {
            Log.w("MountedImageFs", "close failed: ${t.message}")
        }
    }
}
