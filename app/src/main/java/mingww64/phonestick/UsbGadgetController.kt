package mingww64.phonestick

import android.content.Context
import android.net.Uri
import android.util.Log
import com.topjohnwu.superuser.Shell

object UsbGadgetController {
    private const val TAG = "UsbGadgetController"

    // configfs 挂载点每次开机稳定（init 挂载后不变）。getMountStatus 是状态
    // 轮询路径，每次探测要 2-3 个 root shell 往返，进程内缓存掉。
    // 只缓存非空结果：shell 未就绪时探测失败可以下次重试。
    @Volatile
    private var cachedConfigFs: String? = null

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
        cachedConfigFs?.let { return it }
        val mountRes = Shell.cmd("mount -t configfs 2>/dev/null | head -n1 | awk '{print \$3}'").exec()
        val mountPath = mountRes.out.firstOrNull()?.trim() ?: ""
        if (mountPath.isNotEmpty()) {
            Log.d(TAG, "ConfigFS from mount: $mountPath")
            cachedConfigFs = mountPath
            return mountPath
        }
        for (path in listOf("/sys/kernel/config", "/config")) {
            val res = Shell.cmd("[ -d $path/usb_gadget ] && echo OK").exec()
            if (res.out.contains("OK")) {
                Log.d(TAG, "ConfigFS found at: $path")
                cachedConfigFs = path
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
        // 探测失败时按最常见的 /config 兜底（与 tryConfigfsDirectMount 同规则）
        val cfgRoot = configFs.ifEmpty { "/config" }
        Log.d(TAG, "Mount: configFs=$configFs")

        // 尽早建好自建 gadget 树（ps0）并链接 function——必须在任何
        // setprop sys.usb.config 之前！实测（一加 15 / MI 6）：ROM 的 USB
        // 栈发生"配置回退"（如 HAL 尝试 mass_storage 失败落到 MTP）后，
        // configfs 的符号链接创建会被内核门控拒绝（EPERM），而门关之前
        // 创建的链接持续有效。之后哪怕 setprop 路径失败，回退流程只需
        // 解绑 g1 → 重绑 ps0 即可挂载（详见 docs/handoff.md 门控分析）。
        // LUN 属性写入不受门控影响，此处一并写好。
        ensureOwnGadget(escapedPath, roFlag, cdromFlag, configFs)

        // --- setprop sys.usb.config → wait for init to create LUN → write image ---
        // Android's init system watches sys.usb.config and automatically creates the
        // mass_storage gadget function when the property includes "mass_storage".
        // This is how DriveDroid works and is the correct approach for devices like Xiaomi 15
        // where mass_storage is not pre-present in the gadget tree.
        // If init doesn't create a LUN within 5s, the kernel doesn't support this mode.
        //（历史上的"自建 gadget 兜底"已于 2.4.3 删除，现由下方 configfs-direct 回退承担——docs/handoff.md）
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
        // minSdk 24（Android 7.0+）起 configfs gadget 是唯一存在的框架，
        // 老 sysfs（/sys/class/android_usb）只在 Android 4.x 内核存在，不再探测
        val lunCandidates = listOf(
            "$cfgRoot/usb_gadget/g1/functions/mass_storage.0/lun.0",
            "$cfgRoot/usb_gadget/g1/functions/mass_storage.0/lun"
        )
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
            if (foundLun.isEmpty()) {
                foundLun = Shell.cmd(
                    "for g in $cfgRoot/usb_gadget/*/functions/mass_storage.*/lun.*; do [ -d \"\$g\" ] && echo \"\$g\"; done 2>/dev/null"
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
                // HAL 不支持 mass_storage 的 ROM（MI 6 类原生：init.rc 已删掉
                // 该段）走 configfs 直接管线。注意：前面的 setprop 可能触发了
                // ROM 的 MTP 回退（mass_storage,adb 无法满足时 HAL 落到纯 MTP，
                // adb 直接消失），所以先恢复原 USB 配置，再做解绑→链接→重绑。
                Log.w(TAG, "LUN written but gadget not active — trying configfs-direct mount")
                val ownLun = tryConfigfsDirectMount(configFs, escapedPath, roFlag, cdromFlag)
                if (ownLun != null && pollGadgetActive(ownLun)) {
                    return Pair(true, "挂载成功（ConfigFS 直接管线，$ownLun）")
                }
                Shell.cmd("setprop sys.usb.config adb").exec()
                MountDiag.write("lun-stale", "HAL 未生效的 LUN 状态下，configfs-direct 回退失败")
                Log.w(TAG, "configfs-direct mount also failed")
                return Pair(false, "挂载失败：镜像已写入 LUN，但 gadget 未生效（UDC 未绑定或配置未链接），\nconfigfs 直接管线也未成功。\n可尝试插拔数据线后重试。")
            }
            Log.w(TAG, "LUN found but write failed: ${writeRes.err}")
            return Pair(false, "挂载失败：LUN 已出现，但内核拒绝了该镜像路径。\n${writeRes.err.joinToString("\n")}")
        }

        Log.w(TAG, "No LUN appeared after setprop — trying configfs-direct mount (self-built gadget, no adb dependency)")
        val ownLun = tryConfigfsDirectMount(configFs, escapedPath, roFlag, cdromFlag)
        if (ownLun != null && pollGadgetActive(ownLun)) {
            return Pair(true, "挂载成功（ConfigFS 直接管线，$ownLun）")
        }
        Shell.cmd("setprop sys.usb.config adb").exec()
        Log.w(TAG, "configfs-direct mount failed (no HAL-created LUN)")
        MountDiag.write("no-lun", "setprop 后无 LUN 出现，configfs-direct 回退失败（无开发者选项场景主路径）")
        return Pair(false, "挂载失败：内核没有创建 mass_storage LUN，configfs 直接管线也未成功。\n" +
            "设备可能不支持 USB 大容量存储 gadget（内核缺 f_mass_storage / CONFIG_USB_F_MASS_STORAGE）。")
    }

    /**
     * 尽早建好自建 gadget（ps0）树并链接 function——必须在本次开机的
     * 第一次 setprop sys.usb.config 之前调用！实测（一加 15 / MI 6）：
     * ROM 的 USB 栈发生"配置回退"（如 HAL 尝试 mass_storage 失败落到
     * MTP）后，configfs 符号链接创建会被内核门控拒绝（EPERM），而门关
     * 之前创建的链接持续有效。之后哪怕 setprop 路径失败，回退只需
     * 解绑 g1 → 重绑 ps0。LUN 属性写入不受门控影响。树建成后就地
     * 复用（幂等）。详见 docs/handoff.md 门控分析。
     */
    private fun ensureOwnGadget(
        escapedPath: String, roFlag: String, cdromFlag: String, configFs: String
    ) {
        val base = if (configFs.isNotEmpty()) "$configFs/usb_gadget/ps0" else "/config/usb_gadget/ps0"
        val fn = "$base/functions/mass_storage.0"
        val cfg = "$base/configs/b.1"
        Shell.cmd(
            "mkdir -p $fn/lun.0 $cfg 2>/dev/null || true",
            "[ -f $base/idVendor ] || { echo 0x18d1 > $base/idVendor 2>/dev/null || true; echo 0x4ee7 > $base/idProduct 2>/dev/null || true; }",
            // 链接只在门开时能创建（本开机周期首次 setprop 前）；已存在则跳过。
            // 必须 cd+相对路径：vendor 伪 fs（MI 6 的 /config）拒绝绝对路径
            // symlink，此前这里用绝对路径等于在 MI 6 上从未建成过
            "cd $base 2>/dev/null || true",
            "[ -e configs/b.1/mass_storage.0 ] || ln -s functions/mass_storage.0 configs/b.1/mass_storage.0 2>/dev/null || true",
            "cd /",
            "echo '$roFlag' > $fn/lun.0/ro 2>/dev/null || true",
            "echo '$cdromFlag' > $fn/lun.0/cdrom 2>/dev/null || true",
            "echo 1 > $fn/lun.0/nofua 2>/dev/null || true",
            "echo '$escapedPath' > $fn/lun.0/file 2>/dev/null || true"
        ).exec()
    }

    /**
     * ConfigFS 直接管线的回退执行（DdogezD/phonestick 同款时序，无 adb
     * 依赖）：
     * 1. 链接 function——g1 仍绑定 UDC、本次开机未写 sys.usb.config 时
     *    门是开的（实测门控只在 setprop/框架动过 USB 配置后武装）
     * 2. 解绑 g1（adb 瞬断）
     * 3. 绑定 ps0（配置内已含链接）→ 主机出盘
     * 返回 ps0 的 LUN 路径（供 pollGadgetActive 验证），失败返回 null。
     */
    private fun tryConfigfsDirectMount(
        configFs: String, escapedPath: String, roFlag: String, cdromFlag: String
    ): String? {
        val root = configFs.ifEmpty { "/config" }
        val base = "$root/usb_gadget/ps0"
        val g1 = "$root/usb_gadget/g1"
        val udcDev = getUdcName()
        if (udcDev.isEmpty()) return null
        // 完全自愈：ROM 的 USB 状态机在配置回退/模式切换时会清掉外来 gadget
        // 树（MI 6 实测 ps0 被删），所以每次回退都完整重建。全程 cd+相对路径
        // （vendor 伪 fs 拒绝绝对路径 symlink）+ 零 setprop（不触发 init/HAL
        // 状态机——这是此前失败的根源，DdogezD/phonestick 同款时序）。
        val res = Shell.cmd(
            "mkdir -p functions/mass_storage.0/lun.0 configs/b.1 2>/dev/null || true",
            "cd $base 2>/dev/null || exit 1",
            "[ -f idVendor ] || { echo 0x18d1 > idVendor 2>/dev/null || true; echo 0x4ee7 > idProduct 2>/dev/null || true; }",
            "echo '$escapedPath' > functions/mass_storage.0/lun.0/file 2>/dev/null || true",
            "echo '$roFlag' > functions/mass_storage.0/lun.0/ro 2>/dev/null || true",
            "echo '$cdromFlag' > functions/mass_storage.0/lun.0/cdrom 2>/dev/null || true",
            "echo 1 > functions/mass_storage.0/lun.0/nofua 2>/dev/null || true",
            "echo '' > ../g1/UDC 2>/dev/null || true",
            "sleep 0.5",
            "[ -e configs/b.1/mass_storage.0 ] || ln -s functions/mass_storage.0 configs/b.1/mass_storage.0 2>/dev/null || true",
            "echo '$udcDev' > UDC 2>/dev/null || true",
            "sleep 1",
            "if [ -n \"\$(cat UDC 2>/dev/null)\" ] && [ -e configs/b.1/mass_storage.0 ]; then echo 'STATUS=OK'; else echo 'STATUS=FAIL'; fi",
            // libsu 持久 shell 跨 cmd 调用共享 cwd，上面 cd 进了 base，收尾退回根目录
            "cd /"
        ).exec()
        val ok = res.out.any { it.contains("STATUS=OK") }
        Log.d(TAG, "configfs-direct mount: ${res.out.joinToString(" | ")} err=${res.err}")
        return if (ok) "$base/functions/mass_storage.0/lun.0" else null
    }

    /**
     * ACTIVE 判定的共享 shell 实现：gadget 绑定了 UDC（`UDC` 非空）且该
     * function 已链进某个 configuration（同名链接，或 readlink 解析目标匹配——
     * MIUI/ColorOS 等 HAL 起的链接名不等于函数名，如 function2/f2）。
     * pollGadgetActive 与 getMountStatus 必须同一套判定，否则 UI 状态和
     * 真实状态会打架。用法：ACTIVE=$(ps_gadget_active <lun路径>)，输出 0/1。
     */
    private val gadgetActiveShellFn = listOf(
        "ps_gadget_active() {",
        "  LUN=\"\$1\"",
        "  ACTIVE=0",
        "  case \"\$LUN\" in",
        "    */functions/*)",
        "      GADGET=\"\${LUN%/functions/*}\"",
        "      FUNC=\"\${LUN#*/functions/}\"; FUNC=\"\${FUNC%%/*}\"",
        "      if [ -n \"\$(cat \"\$GADGET/UDC\" 2>/dev/null)\" ]; then",
        "        for cfg in \"\$GADGET\"/configs/*/; do",
        "          if [ -e \"\${cfg}\$FUNC\" ]; then ACTIVE=1; break; fi",
        "          for link in \"\${cfg}\"*; do",
        "            case \"\$(readlink -f \"\$link\" 2>/dev/null)\" in",
        "              */functions/\$FUNC) ACTIVE=1; break 2 ;;",
        "            esac",
        "          done",
        "        done",
        "      fi",
        "      ;;",
        "  esac",
        "  echo \"\$ACTIVE\"",
        "}"
    ).joinToString("\n")

    /**
     * 轮询 LUN 所在 gadget 是否已真正对主机生效：HAL 重新配置 USB（比如只留
     * adb）时会摘掉 mass_storage 链接，此时即使 UDC 还绑着（adb 在用）、
     * lun.0/file 有残留，主机也看不到盘。判定条件见 [gadgetActiveShellFn]。
     * 最多等 [timeoutMs]，每 500ms 一轮。
     */
    private fun pollGadgetActive(lunPath: String, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val res = Shell.cmd(
                gadgetActiveShellFn,
                "ACTIVE=\$(ps_gadget_active '$lunPath')",
                "echo \"ACTIVE=\$ACTIVE\""
            ).exec()
            if (res.out.firstOrNull { it.startsWith("ACTIVE=") } == "ACTIVE=1") {
                return true
            }
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(500)
        }
    }

    fun unmountImage(context: Context? = null): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, "无法获取 Root 权限")
        }

        val configFs = getConfigFsPath()
        val udcName = getUdcName()
        val cfgRoot = configFs.ifEmpty { "/config" }

        val unmountScript = buildList {
            // Restore g1 gadget UDC（HAL 托管树；sys.usb.config 的恢复在下面统一做）
            if (udcName.isNotEmpty()) {
                add("echo '$udcName' > $cfgRoot/usb_gadget/g1/UDC 2>/dev/null || true")
            }
            // Clear file from all known LUN paths using glob（含自建 ps0）
            add("for lun in $cfgRoot/usb_gadget/*/functions/mass_storage.*/lun.*; do")
            add("  [ -f \"\$lun/file\" ] && echo '' > \"\$lun/file\" 2>/dev/null || true")
            add("done")
            // 自建 ps0 gadget：解绑（树保留，下次挂载复用；g1 由 setprop 恢复）
            add("echo '' > $cfgRoot/usb_gadget/ps0/UDC 2>/dev/null || true")
            // configfs 直接管线：清理我们链进 configuration 的 mass_storage
            // （存在才动：解绑 → 摘链接 → 重绑）。HAL 托管的设备此时链接已被
            // HAL 摘掉，此循环为空转。
            add("for g in $cfgRoot/usb_gadget/*/; do")
            add("  case \"\$g\" in *usb_gadget/ps0/) continue;; esac")
            add("  for c in \"\$g\"configs/*/; do")
            add("    if [ -e \"\${c}mass_storage.0\" ]; then")
            add("      echo '' > \"\$g\"UDC 2>/dev/null")
            add("      rm -f \"\${c}mass_storage.0\"")
            add("      echo '$udcName' > \"\$g\"UDC 2>/dev/null")
            add("    fi")
            add("  done")
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
        val cfgRoot = configFs.ifEmpty { "/config" }

        val checkScript = buildList {
            add(gadgetActiveShellFn)
            add("LUN_FILE=''")
            add("LUN_RO=''")
            add("LUN_CD=''")
            add("LUN_PATH=''")
            add("for lun in $cfgRoot/usb_gadget/*/functions/mass_storage.*/lun.*; do")
            add("  if [ -f \"\$lun/file\" ]; then")
            add("    CONTENT=\$(cat \"\$lun/file\" 2>/dev/null)")
            add("    if [ -n \"\$CONTENT\" ]; then")
            // P2：lun.0/file 非空只说明内核记住了这个文件，不代表主机真的在用。
            // 还要满足 ACTIVE 判定（UDC 绑定 + configuration 链接）——与
            // pollGadgetActive 同源（gadgetActiveShellFn），否则 UI 状态和
            // 真实状态会打架。
            add("      ACTIVE=\$(ps_gadget_active \"\$lun\")")
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
