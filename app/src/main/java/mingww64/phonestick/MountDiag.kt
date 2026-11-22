package mingww64.phonestick

import com.topjohnwu.superuser.Shell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 挂载失败的自动诊断：把完整环境/步骤/自检结果写到
 * /sdcard/PhoneStick/diag-<时间戳>.txt（root 写入，MTP/文件管理器可见），
 * 供无 adb 环境下定位（尤其"开发者选项关闭"场景）。
 *
 * 自检链：普通 ln 尝试 → 注入 SELinux 宽容（ksud/magiskpolicy）→ 重试 →
 * 两者的结果能一锤定音区分"SELinux 上下文问题"与"内核级门控"。
 */
object MountDiag {

    fun write(tag: String, extra: String = "") {
        try {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val body = buildString {
                appendLine("== PhoneStick 挂载诊断 $tag ==")
                appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine()
                appendLine("-- 环境 --")
                for (p in listOf(
                    "ro.product.model", "ro.build.version.release", "ro.build.version.sdk",
                    "ro.product.brand", "sys.usb.config", "sys.usb.state", "ro.boot.verifiedbootstate"
                )) appendLine("getprop $p = " + Shell.cmd("getprop $p").exec().out.firstOrNull())
                appendLine("getenforce = " + Shell.cmd("getenforce").exec().out.firstOrNull())
                appendLine("root 身份 = " + Shell.cmd("id").exec().out.firstOrNull())
                appendLine("configfs 挂载 = " + Shell.cmd("mount | grep -i configfs").exec().out.joinToString(" ; "))
                appendLine()
                appendLine("-- gadget 树状态 --")
                appendLine(Shell.cmd(
                    "for g in /config/usb_gadget/*/; do echo \"== \$g ==\"; " +
                        "ls \$g/functions 2>/dev/null; " +
                        "for c in \${g}configs/*/; do ls -l \"\$c\" 2>/dev/null | grep ^l; done; " +
                        "echo \"UDC=\$(cat \${g}UDC 2>/dev/null)\"; done"
                ).exec().out.joinToString("\n"))
                appendLine()
                appendLine("-- 自检：SELinux 宽容注入前后各试一次链接 --")
                val src = "/config/usb_gadget/g1/functions/mass_storage.0"
                val dst = "/config/usb_gadget/g1/configs/b.1/ms_diag"
                appendLine("尝试1（现状）: " + Shell.cmd(
                    "ln -sf $src $dst 2>&1; echo rc=\$?; rm -f $dst 2>/dev/null"
                ).exec().out.joinToString(" | "))
                val policyTool = when {
                    Shell.cmd("which ksud 2>/dev/null || [ -x /data/adb/ksud ]").exec().isSuccess ->
                        "/data/adb/ksud sepolicy patch \"permissive ksu\""
                    else -> "magiskpolicy --live \"permissive magisk\" 2>&1"
                }
                appendLine("策略注入: $policyTool → " + Shell.cmd(policyTool).exec().out.joinToString(" | "))
                appendLine("尝试2（宽容后）: " + Shell.cmd(
                    "ln -sf $src $dst 2>&1; echo rc=\$?; rm -f $dst 2>/dev/null"
                ).exec().out.joinToString(" | "))
                appendLine()
                if (extra.isNotEmpty()) {
                    appendLine("-- 失败上下文 --")
                    appendLine(extra)
                    appendLine()
                }
                appendLine("-- dmesg 尾部（AVC/gadget 相关）--")
                appendLine(Shell.cmd("dmesg | grep -iE \"avc|gadget|configfs|usb\" | tail -20").exec().out.joinToString("\n"))
            }
            val b64 = android.util.Base64.encodeToString(
                body.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            Shell.cmd(
                "mkdir -p /sdcard/PhoneStick 2>/dev/null || true",
                "echo '$b64' | base64 -d > /sdcard/PhoneStick/diag-$tag-$ts.txt && echo DIAG_OK"
            ).exec()
        } catch (e: Exception) {
            android.util.Log.w("MountDiag", "诊断写入失败: ${e.message}")
        }
    }
}
