package mingww64.phonestick.fs

import java.io.Closeable
import java.io.RandomAccessFile

/**
 * 镜像（或其分区）的随机读取抽象。
 *
 * 之所以要这一层：镜像文件在 /data/media/0 下，属主是 root/media_rw，
 * app 进程既读不到（SELinux + 权限），又要支持"镜像被 root 挂载后再读"，
 * 所以真实设备上走 [SuBlockIo]（root shell 读），本地单元测试走
 * [RandomAccessBlockIo]（普通文件读）。上层解析器只依赖这个接口。
 */
interface BlockIo : Closeable {
    val size: Long

    /** 从 off 读 len 字节到 buf；越界部分补 0，不抛异常 */
    fun readFully(off: Long, buf: ByteArray, bufOff: Int, len: Int)

    /** 从 off 读 len 字节；不足 len 时返回实际读到的字节数（可能为空数组） */
    fun readAt(off: Long, len: Int): ByteArray
}

object Bytes {
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    fun le16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)

    /** 32 位无符号以小端读出（用 Long 承载，避免符号问题） */
    fun le32(b: ByteArray, o: Int): Long =
        u8(b, o).toLong() or (u8(b, o + 1).toLong() shl 8) or
            (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)

    fun be16(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)

    fun be32(b: ByteArray, o: Int): Long = (be16(b, o).toLong() shl 16) or be16(b, o + 2).toLong()

    fun matches(b: ByteArray, o: Int, s: String): Boolean {
        if (o < 0 || o + s.length > b.size) return false
        for (i in s.indices) {
            if (b[o + i] != s[i].code.toByte()) return false
        }
        return true
    }

    fun ascii(b: ByteArray, o: Int, len: Int): String {
        val n = minOf(len, b.size - o).coerceAtLeast(0)
        if (n <= 0) return ""
        return String(b, o, n, Charsets.US_ASCII)
    }
}

/** 普通文件随机读（用于 JVM 单元测试，以及理论上 app 有权限直接读的镜像） */
class RandomAccessBlockIo(private val raf: RandomAccessFile) : BlockIo {

    override val size: Long get() = raf.length()

    @Synchronized
    override fun readFully(off: Long, buf: ByteArray, bufOff: Int, len: Int) {
        var pos = off
        var dst = bufOff
        var remain = len
        raf.seek(off)
        while (remain > 0) {
            val n = raf.read(buf, dst, remain)
            if (n <= 0) break
            pos += n
            dst += n
            remain -= n
        }
        if (remain > 0) java.util.Arrays.fill(buf, dst, dst + remain, 0)
    }

    @Synchronized
    override fun readAt(off: Long, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val b = ByteArray(len)
        readFully(off, b, 0, len)
        return b
    }

    override fun close() {
        raf.close()
    }
}
