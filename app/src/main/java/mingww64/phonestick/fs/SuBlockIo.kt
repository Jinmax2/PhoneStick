package mingww64.phonestick.fs

import com.topjohnwu.superuser.io.SuRandomAccessFile

/**
 * 通过 root shell 随机读镜像。
 *
 * 为什么不用 Java 的 File/RandomAccessFile：
 *   镜像在 /data/media/0/PhoneStick 下，属主是 root:everybody(9997)，权限 rw-rw----，
 *   app 进程既不是属主也不在组里；即使放开权限位，SELinux 也会拦
 *   （media_rw_data_file → untrusted_app）。所以在真机上只能经 su 读。
 *
 * 为什么要缓存窗口：
 *   libsu 的 SuRandomAccessFile 每次 seek 都可能重开一条 su 管道，
 *   解析文件系统时是大量小随机读（几十到几百次），逐次开管道会慢到不可用。
 *   这里按 512KB 窗口预读，把 su 往返次数压到个位数。
 */
class SuBlockIo(path: String, private val windowSize: Int = 512 * 1024) : BlockIo {

    private val raf = SuRandomAccessFile.open(path, "r")
    private val window = ByteArray(windowSize)
    private var windowStart = -1L
    private var windowLen = 0

    override val size: Long = try {
        raf.length()
    } catch (e: Exception) {
        runCatching { raf.close() }
        throw e
    }

    @Synchronized
    override fun readFully(off: Long, buf: ByteArray, bufOff: Int, len: Int) {
        var pos = off
        var dst = bufOff
        var remain = len
        while (remain > 0) {
            val start = (pos / windowSize) * windowSize
            if (start != windowStart) refill(start)
            val inWindow = (pos - windowStart).toInt()
            if (inWindow < 0 || inWindow >= windowLen) {
                // 已经越过文件末尾（稀疏镜像的空洞由内核补零，这里不会发生；
                // 越界时补零，让解析器看到全零而不是异常）
                java.util.Arrays.fill(buf, dst, dst + remain, 0)
                return
            }
            val n = minOf(remain, windowLen - inWindow)
            System.arraycopy(window, inWindow, buf, dst, n)
            pos += n
            dst += n
            remain -= n
        }
    }

    @Synchronized
    override fun readAt(off: Long, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val b = ByteArray(len)
        readFully(off, b, 0, len)
        return b
    }

    private fun refill(start: Long) {
        windowStart = start
        windowLen = 0
        try {
            raf.seek(start)
        } catch (e: Exception) {
            return
        }
        var got = 0
        while (got < window.size) {
            val n = try {
                raf.read(window, got, window.size - got)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) break
            got += n
        }
        windowLen = got
    }

    override fun close() {
        runCatching { raf.close() }
    }
}
