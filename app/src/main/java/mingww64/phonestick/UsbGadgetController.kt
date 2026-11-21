package mingww64.phonestick

import android.content.Context
import android.net.Uri
import android.util.Log
import com.topjohnwu.superuser.Shell

object UsbGadgetController {
    private const val TAG = "UsbGadgetController"

    data class MountStatus(
        val isMounted: Boolean = false,
        val currentFile: String = "",
        val isReadOnly: Boolean = false,
        val isCdrom: Boolean = false,
        val lunPath: String = ""
    )

    fun isRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root access: ${e.message}", e)
            false
        }
    }

    /**
     * Discover the real UDC (USB Device Controller) name by listing /sys/class/udc/.
     * Qualcomm devices (including Xiaomi 15) use names like "a600000.dwc3" or "11201000.dwc3".
     * Falls back to getprop sys.usb.controller if the directory is empty.
     */
    private fun getUdcName(): String {
        val udcListRes = Shell.cmd("ls /sys/class/udc/ 2>/dev/null | head -n1").exec()
        val udcFromSysfs = udcListRes.out.firstOrNull()?.trim() ?: ""
        if (udcFromSysfs.isNotEmpty()) {
            Log.d(TAG, "Discovered UDC from /sys/class/udc/: $udcFromSysfs")
            return udcFromSysfs
        }
        val udcFromProp = Shell.cmd("getprop sys.usb.controller").exec().out.firstOrNull()?.trim() ?: ""
        Log.d(TAG, "UDC from sys.usb.controller: $udcFromProp")
        return udcFromProp
    }

    /**
     * Find the configfs mount root. Tries parsing mount output first, then falls back
     * to well-known paths used across different Android vendors (Qualcomm, MediaTek, etc.).
     */
    private fun getConfigFsPath(): String {
        val mountRes = Shell.cmd("mount -t configfs 2>/dev/null | head -n1 | awk '{print \$3}'").exec()
        val mountPath = mountRes.out.firstOrNull()?.trim() ?: ""
        if (mountPath.isNotEmpty()) {
            Log.d(TAG, "ConfigFS from mount: $mountPath")
            return mountPath
        }
        for (path in listOf("/sys/kernel/config", "/config")) {
            val res = Shell.cmd("[ -d $path/usb_gadget ] && echo OK").exec()
            if (res.out.contains("OK")) {
                Log.d(TAG, "ConfigFS found at: $path")
                return path
            }
        }
        Log.w(TAG, "ConfigFS not found")
        return ""
    }

    fun mountImage(context: Context, pathOrUri: String, readOnly: Boolean, cdrom: Boolean): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, "无法获取 Root 权限")
        }

        var resolvedPath = pathOrUri
        if (pathOrUri.startsWith("content://")) {
            resolvedPath = UriPathResolver.getRealPathFromUri(context, Uri.parse(pathOrUri))
        }

        // 统一转成 /data/media 原始路径。
        // gadget 绑定 FUSE 路径（/sdcard、/storage/emulated）会带来额外的
        // 权限层与性能开销，直接用原始路径更稳。
        resolvedPath = ImageStore.toRawPath(resolvedPath)

        val escapedPath = resolvedPath.replace("'", "'\\''")
        val existsCmd = Shell.cmd(
            "if [ -f '$escapedPath' ]; then echo EXISTS; echo \"SIZE=\$(stat -c %s '$escapedPath' 2>/dev/null)\"; " +
                "else echo MISSING; fi"
        ).exec()
        if (!existsCmd.out.contains("EXISTS")) {
            return Pair(false, "镜像文件不存在：$resolvedPath")
        }
        // 空文件绑进 LUN 主机端会看到一个 0 扇区的盘，后续所有操作都会莫名失败，
        // 不如在这里明确拦住
        val size = existsCmd.out.lastOrNull { it.startsWith("SIZE=") }
            ?.removePrefix("SIZE=")?.trim()?.toLongOrNull() ?: 0L
        if (size <= 0) {
            return Pair(false, "镜像文件为空：$resolvedPath")
        }

        // 兜底：确保 kernel 侧的 gadget 线程有权限写这个文件。
        // 若镜像不在 media_rw_data_file 名下（例如残留在 app_data_file），
        // 这里会放开权限位，避免主机端写分区表报 I/O error。
        Shell.cmd(
            "chmod 0666 '$escapedPath' 2>/dev/null || true"
        ).exec()

        val roFlag = if (readOnly) "y" else "n"
        val cdromFlag = if (cdrom) "y" else "n"
        val configFs = getConfigFsPath()
        Log.d(TAG, "Mount: configFs=$configFs")

        // --- setprop sys.usb.config → wait for init to create LUN → write image ---
        // Android's init system watches sys.usb.config and automatically creates the
        // mass_storage gadget function when the property includes "mass_storage".
        // This is how DriveDroid works and is the correct approach for devices like Xiaomi 15
        // where mass_storage is not pre-present in the gadget tree.
        // If init doesn't create a LUN within 5s, the kernel doesn't support this mode.
        //（曾经还有一个自建 ConfigFS gadget 的"Strategy 2"兜底，实测在 Android 17 上
        // 被 SELinux 拦死、从未成功过，2.4.3 起删除——详见 docs/handoff.md）
        val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull()?.trim() ?: ""
        if (!curConfig.contains("mass_storage")) {
            val prefs = context.getSharedPreferences("phonestick", Context.MODE_PRIVATE)
            prefs.edit().putString("orig_usb_config", curConfig).apply()
            val targetConfig = when {
                curConfig.contains("adb") -> "mass_storage,adb"
                curConfig.isNotEmpty()    -> "mass_storage,$curConfig"
                else                      -> "mass_storage"
            }
            Log.d(TAG, "Strategy 1: setprop sys.usb.config $targetConfig (was: $curConfig)")
            Shell.cmd("setprop sys.usb.config $targetConfig").exec()
        }

        // Poll up to 5s for a mass_storage LUN to appear (created by init)
        val lunCandidates = buildList {
            if (configFs.isNotEmpty()) {
                add("$configFs/usb_gadget/g1/functions/mass_storage.0/lun.0")
                add("$configFs/usb_gadget/g1/functions/mass_storage.0/lun")
            }
            add("/sys/class/android_usb/android0/f_mass_storage/lun0")
            add("/sys/class/android_usb/android0/f_mass_storage/lun")
        }
        var foundLun = ""
        for (attempt in 1..10) {
            Thread.sleep(500)
            for (lun in lunCandidates) {
                if (Shell.cmd("[ -d '$lun' ] && echo EXISTS").exec().out.contains("EXISTS")) {
                    foundLun = lun
                    break
                }
            }
            // Also glob in case init placed it at a non-standard path
            if (foundLun.isEmpty() && configFs.isNotEmpty()) {
                foundLun = Shell.cmd(
                    "for g in $configFs/usb_gadget/*/functions/mass_storage.*/lun.*; do [ -d \"\$g\" ] && echo \"\$g\"; done 2>/dev/null"
                ).exec().out.firstOrNull { it.isNotBlank() }?.trim() ?: ""
            }
            if (foundLun.isNotEmpty()) {
                Log.d(TAG, "Strategy 1: LUN appeared at $foundLun after ${attempt * 500}ms")
                break
            }
            Log.d(TAG, "Strategy 1: waiting for LUN... attempt $attempt/10")
        }

        if (foundLun.isNotEmpty()) {
            val writeRes = Shell.cmd(
                "echo '$roFlag' > '$foundLun/ro' 2>/dev/null || true",
                "echo '$cdromFlag' > '$foundLun/cdrom' 2>/dev/null || true",
                // nofua=1 让 gadget 不再对每次写做同步落盘。
                // 在 f2fs/ext4 上，主机 fdisk 的 SYNCHRONIZE CACHE 会转成
                // fdatasync，一旦失败主机就收到 I/O error（分区表写不进去）。
                "echo 1 > '$foundLun/nofua' 2>/dev/null || true",
                "echo '$escapedPath' > '$foundLun/file'",
                "CHECK=\$(cat '$foundLun/file' 2>/dev/null)",
                "if [ -n \"\$CHECK\" ]; then echo 'S1_SUCCESS'; else echo 'S1_FAILED'; fi"
            ).exec()
            if (writeRes.out.contains("S1_SUCCESS")) {
                // LUN 拿到文件 ≠ 主机能看到盘：HAL 还要把 function 链进
                // configuration 并绑定 UDC。等这个状态稳定（≤5s）再报成功，
                // 判定条件与 getMountStatus() 保持一致，避免 UI 与真实状态打架。
                if (pollGadgetActive(foundLun)) {
                    return Pair(true, "挂载成功（sys.usb.config，$foundLun）")
                }
                Log.w(TAG, "LUN written but gadget not active (UDC unbound or config link missing) after 5s")
                return Pair(false, "挂载失败：镜像已写入 LUN，但 gadget 未生效（UDC 未绑定或配置未链接）。\n可尝试插拔数据线后重试。")
            }
            Log.w(TAG, "LUN found but write failed: ${writeRes.err}")
            return Pair(false, "挂载失败：LUN 已出现，但内核拒绝了该镜像路径。\n${writeRes.err.joinToString("\n")}")
        }

        Log.w(TAG, "No LUN appeared after setprop — init does not handle mass_storage on this device")
        return Pair(false, "挂载失败：内核没有创建 mass_storage LUN。\n" +
            "设备可能不支持 USB 大容量存储 gadget（内核缺 f_mass_storage / CONFIG_USB_F_MASS_STORAGE）。")
    }

    /**
     * 判断 LUN 所在的 gadget 是否已真正对主机生效：
     * - configfs 路径：gadget 绑定了 UDC（`UDC` 非空）且该 function 已链进某个
     *   configuration。HAL 重新配置 USB（比如只留 adb）时会摘掉 mass_storage
     *   链接，此时即使 UDC 还绑着（adb 在用）、lun.0/file 有残留，主机也看不到盘。
     * - 老 sysfs（android_usb）路径：看 android0/enable。
     * 只做一次检查（最多等 [timeoutMs]，每 500ms 一轮）。
     */
    private fun pollGadgetActive(lunPath: String, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val script = buildList {
                add("LUN='$lunPath'")
                add("ACTIVE=0")
                add("case \"\$LUN\" in")
                add("  */functions/*)")
                add("    GADGET=\"\${LUN%/functions/*}\"")
                add("    FUNC=\"\${LUN#*/functions/}\"; FUNC=\"\${FUNC%%/*}\"")
                add("    if [ -n \"\$(cat \"\$GADGET/UDC\" 2>/dev/null)\" ]; then")
                add("      for cfg in \"\$GADGET\"/configs/*/; do")
                add("        if [ -e \"\${cfg}\$FUNC\" ]; then ACTIVE=1; break 2; fi")
                // MIUI 等 HAL 起的链接名不等于函数名（如 function2 -> mass_storage.0），
                // 再按解析后的目标路径兜底认一遍
                add("        for link in \"\${cfg}\"*; do")
                add("          case \"\$(readlink -f \"\$link\" 2>/dev/null)\" in")
                add("            */functions/\$FUNC) ACTIVE=1; break 2 ;;")
                add("          esac")
                add("        done")
                add("      done")
                add("    fi")
                add("    ;;")
                add("  /sys/class/android_usb/*)")
                add("    [ \"\$(cat /sys/class/android_usb/android0/enable 2>/dev/null)\" = \"1\" ] && ACTIVE=1")
                add("    ;;")
                add("esac")
                add("echo \"ACTIVE=\$ACTIVE\"")
            }.toTypedArray()
            val res = Shell.cmd(*script).exec()
            if (res.out.firstOrNull { it.startsWith("ACTIVE=") } == "ACTIVE=1") {
                return true
            }
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(500)
        }
    }

    fun unmountImage(context: Context? = null): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, "Root access not available")
        }

        val configFs = getConfigFsPath()
        val udcName = getUdcName()

        val unmountScript = buildList {
            if (configFs.isNotEmpty()) {
                // 清掉 2.4.2 及以前自建 swy gadget 的残留（那套方案已删，
                // 但老版本可能留下半截目录树，会干扰排查）
                add("echo '' > $configFs/usb_gadget/swy/UDC 2>/dev/null || true")
                add("if [ -d $configFs/usb_gadget/swy ]; then cd $configFs/usb_gadget/swy 2>/dev/null && rm -f configs/swyconfig.1/mass_storage.0 2>/dev/null; rmdir configs/swyconfig.1/strings/0x409 2>/dev/null; rmdir configs/swyconfig.1 2>/dev/null; rmdir functions/mass_storage.0 2>/dev/null; rmdir strings/0x409 2>/dev/null; cd ..; rmdir swy 2>/dev/null; fi || true")
                // Restore g1 gadget UDC
                if (udcName.isNotEmpty()) {
                    add("echo '$udcName' > $configFs/usb_gadget/g1/UDC 2>/dev/null || true")
                }
            }
            // Clear file from all known LUN paths using glob
            add("for lun in /config/usb_gadget/g1/functions/mass_storage.*/lun.* /sys/kernel/config/usb_gadget/g1/functions/mass_storage.*/lun.* /sys/class/android_usb/android0/f_mass_storage/lun0 /sys/class/android_usb/android0/f_mass_storage/lun; do")
            add("  [ -f \"\$lun/file\" ] && echo '' > \"\$lun/file\" 2>/dev/null || true")
            add("done")
            add("echo 'UNMOUNT_SUCCESS'")
        }.toTypedArray()

        val result = Shell.cmd(*unmountScript).exec()
        if (context != null) {
            restoreOriginalUsbConfig(context)
        } else {
            val defConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: "adb"
            val restoredConfig = defConfig.replace("mass_storage,", "").replace(",mass_storage", "").replace("mass_storage", "none")
            Shell.cmd("setprop sys.usb.config $restoredConfig").exec()
        }

        return if (result.isSuccess) {
            Pair(true, "已卸载")
        } else {
            val errText = result.err.joinToString("\n").ifEmpty { result.out.joinToString("\n").ifEmpty { "退出码 ${result.code}" } }
            Pair(false, "卸载失败（退出码 ${result.code}）：$errText")
        }
    }

    private fun restoreOriginalUsbConfig(context: Context) {
        try {
            val prefs = context.getSharedPreferences("phonestick", Context.MODE_PRIVATE)
            val origConfig = prefs.getString("orig_usb_config", "") ?: ""
            val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: ""

            val restoreTarget = when {
                origConfig.isNotEmpty() -> origConfig
                curConfig.contains("adb") -> "adb"
                else -> "none"
            }
            Log.i(TAG, "Restoring USB config to: $restoreTarget")
            Shell.cmd("setprop sys.usb.config $restoreTarget").exec()
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring sys.usb.config (non-fatal): ${e.message}")
        }
    }

    fun getMountStatus(): MountStatus {
        if (!isRootAvailable()) return MountStatus()

        val configFs = getConfigFsPath()

        // Build LUN scan list dynamically from configfs, plus known fallbacks
        val checkScript = buildList {
            add("LUN_FILE=''")
            add("LUN_RO=''")
            add("LUN_CD=''")
            add("LUN_PATH=''")
            if (configFs.isNotEmpty()) {
                add("for lun in $configFs/usb_gadget/*/functions/mass_storage.*/lun.*; do")
            } else {
                add("for lun in /config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/class/android_usb/android0/f_mass_storage/lun0; do")
            }
            add("  if [ -f \"\$lun/file\" ]; then")
            add("    CONTENT=\$(cat \"\$lun/file\" 2>/dev/null)")
            add("    if [ -n \"\$CONTENT\" ]; then")
            // P2：lun.0/file 非空只说明内核记住了这个文件，不代表主机真的在用。
            // HAL 重新配置 USB 时会摘掉 function 的 configuration 链接（此时
            // UDC 可能仍被 adb 占用着），所以两个条件都要看：
            //   1. gadget 绑定了 UDC（UDC 非空）
            //   2. 该 function 链进了某个 configuration
            // 老 sysfs（android_usb）路径看 android0/enable。
            add("      ACTIVE=0")
            add("      case \"\$lun\" in")
            add("        */functions/*)")
            add("          GADGET=\"\${lun%/functions/*}\"")
            add("          FUNC=\"\${lun#*/functions/}\"; FUNC=\"\${FUNC%%/*}\"")
            add("          if [ -n \"\$(cat \"\$GADGET/UDC\" 2>/dev/null)\" ]; then")
            add("            for cfg in \"\$GADGET\"/configs/*/; do")
            add("              if [ -e \"\${cfg}\$FUNC\" ]; then ACTIVE=1; break 2; fi")
            // MIUI 等 HAL 起的链接名不等于函数名（如 function2 -> mass_storage.0），
            // 再按解析后的目标路径兜底认一遍
            add("              for link in \"\${cfg}\"*; do")
            add("                case \"\$(readlink -f \"\$link\" 2>/dev/null)\" in")
            add("                  */functions/\$FUNC) ACTIVE=1; break 2 ;;")
            add("                esac")
            add("              done")
            add("            done")
            add("          fi")
            add("          ;;")
            add("        /sys/class/android_usb/*)")
            add("          [ \"\$(cat /sys/class/android_usb/android0/enable 2>/dev/null)\" = \"1\" ] && ACTIVE=1")
            add("          ;;")
            add("      esac")
            add("      if [ \"\$ACTIVE\" = \"1\" ]; then")
            add("        LUN_FILE=\"\$CONTENT\"")
            add("        LUN_RO=\$(cat \"\$lun/ro\" 2>/dev/null)")
            add("        LUN_CD=\$(cat \"\$lun/cdrom\" 2>/dev/null)")
            add("        LUN_PATH=\"\$lun\"")
            add("        break")
            add("      fi")
            add("    fi")
            add("  fi")
            add("done")
            add("echo \"STATUS|\$LUN_FILE|\$LUN_RO|\$LUN_CD|\$LUN_PATH\"")
        }.toTypedArray()

        val res = Shell.cmd(*checkScript).exec()
        if (res.isSuccess) {
            val line = res.out.firstOrNull { it.startsWith("STATUS|") }
            if (line != null) {
                val parts = line.split("|")
                if (parts.size >= 5 && parts[1].isNotBlank()) {
                    return MountStatus(
                        isMounted = true,
                        currentFile = parts[1],
                        isReadOnly = parts[2] == "1" || parts[2] == "y",
                        isCdrom = parts[3] == "1" || parts[3] == "y",
                        lunPath = parts[4]
                    )
                }
            }
        }
        return MountStatus()
    }
}
