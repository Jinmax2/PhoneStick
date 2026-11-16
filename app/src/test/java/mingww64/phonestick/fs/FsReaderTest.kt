package mingww64.phonestick.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 用户态解析器的离线测试。
 *
 * 需要先造镜像（见 tools/make-fs-test-images.sh），再把路径传进来：
 *   ./gradlew :app:testReleaseUnitTest \
 *     -Dps.test.image.fat32=/out/fat32.img -Dps.test.image.ntfs=/out/ntfs.img ...
 * 没有提供的镜像会被跳过（assume），所以在普通 CI 上跑也不会红。
 */
class FsReaderTest {

    private fun imagePath(key: String): String? {
        val p = System.getProperty("ps.test.image.$key", "").orEmpty()
        return if (p.isNotEmpty() && File(p).isFile) p else null
    }

    private fun <T> withVolume(key: String, expected: FsKind, block: (BlockIo, VolumeLayout.Part) -> T): T? {
        val path = imagePath(key) ?: return null
        RandomAccessBlockIo(RandomAccessFile(path, "r")).use { io ->
            val layout = VolumeLayout.parse(io)
            val part = layout.usable.firstOrNull { it.fs == expected }
                ?: throw AssertionError("$key: 没识别出 ${expected.label}，实际 ${layout.parts.map { it.fs.label }}")
            return block(io, part)
        }
    }

    @Test
    fun detectsPartitionedLayout() {
        val expected = mapOf(
            "fat32" to FsKind.FAT32,
            "exfat" to FsKind.EXFAT,
            "ntfs" to FsKind.NTFS,
            "ext4" to FsKind.EXT4
        )
        var checked = 0
        for ((key, kind) in expected) {
            val path = imagePath(key) ?: continue
            RandomAccessBlockIo(RandomAccessFile(path, "r")).use { io ->
                val layout = VolumeLayout.parse(io)
                assertEquals("$key 应该是 MBR", "MBR(主分区)", layout.scheme)
                assertEquals("$key 只有一个分区", 1, layout.parts.size)
                assertEquals("$key 分区起始 LBA 2048", 2048L * 512, layout.parts[0].offset)
                assertEquals("$key 文件系统", kind, layout.parts[0].fs)
                checked++
            }
        }
        println("detectsPartitionedLayout: 校验了 $checked 个镜像")
    }

    @Test
    fun detectsGptLayout() {
        val path = imagePath("gpt") ?: return
        RandomAccessBlockIo(RandomAccessFile(path, "r")).use { io ->
            val layout = VolumeLayout.parse(io)
            assertEquals("GPT", layout.scheme)
            assertEquals(1, layout.parts.size)
            assertEquals(2048L * 512, layout.parts[0].offset)
            assertEquals(FsKind.FAT32, layout.parts[0].fs)
        }
    }

    @Test
    fun detectsSuperFloppyLayout() {
        val path = imagePath("ntfsnopt") ?: return
        RandomAccessBlockIo(RandomAccessFile(path, "r")).use { io ->
            val layout = VolumeLayout.parse(io)
            assertEquals("无分区表", layout.scheme)
            assertEquals(1, layout.parts.size)
            assertEquals(0L, layout.parts[0].offset)
            assertEquals(FsKind.NTFS, layout.parts[0].fs)
        }
    }

    @Test
    fun ntfsListsRootDirectory() = Unit.let {
        withVolume("ntfs", FsKind.NTFS) { io, part ->
            NtfsReader(io, part.offset).use { r ->
                val list = r.list("/")
                val names = list.map { it.name }
                println("NTFS root: $names")

                assertTrue("应有 hello.txt，实际 $names", names.contains("hello.txt"))
                assertTrue("应有 dir1，实际 $names", names.contains("dir1"))
                assertTrue("应有 big.bin，实际 $names", names.contains("big.bin"))
                assertTrue("应有中文名文件，实际 $names", names.contains("中文名.txt"))
                assertFalse("不应列出 \$MFT 之类的元文件，实际 $names", names.any { it.startsWith("$") })
                assertFalse("不应出现 . / .. 自引用项，实际 $names", names.any { it == "." || it == ".." })

                val hello = list.first { it.name == "hello.txt" }
                assertFalse(hello.isDir)
                assertTrue("hello.txt 大小：${hello.size}", hello.size > 0)

                val dir1 = list.first { it.name == "dir1" }
                assertTrue(dir1.isDir)
                assertEquals(listOf("nested.txt"), r.list("/dir1").map { it.name })
            }
        }
        Unit
    }

    @Test
    fun ntfsReadsFileContent() = Unit.let {
        withVolume("ntfs", FsKind.NTFS) { io, part ->
            NtfsReader(io, part.offset).use { r ->
                val out = ByteArrayOutputStream()
                val n = r.copyFile("/hello.txt", out)
                assertEquals("hello from phonestick\n", out.toString("UTF-8"))
                assertEquals(out.size().toLong(), n)
            }
        }
        Unit
    }

    @Test
    fun ntfsReadsNonResidentFile() = Unit.let {
        withVolume("ntfs", FsKind.NTFS) { io, part ->
            val md5File = File(imagePath("ntfs")!! + ".big.md5")
            NtfsReader(io, part.offset).use { r ->
                val big = r.list("/").first { it.name == "big.bin" }
                assertEquals(3L * 1024 * 1024, big.size)

                val md = MessageDigest.getInstance("MD5")
                var total = 0L
                val sink = object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        md.update(b.toByte()); total++
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        md.update(b, off, len); total += len
                    }
                }
                r.copyFile("/big.bin", sink)
                assertEquals(3L * 1024 * 1024, total)

                if (md5File.isFile) {
                    val expect = md5File.readText().trim().lowercase()
                    val actual = md.digest().joinToString("") { "%02x".format(it) }
                    assertEquals("big.bin 内容哈希不一致", expect, actual)
                }
            }
        }
        Unit
    }

    @Test
    fun ntfsReadsUtf16Name() = Unit.let {
        withVolume("ntfs", FsKind.NTFS) { io, part ->
            NtfsReader(io, part.offset).use { r ->
                val out = ByteArrayOutputStream()
                r.copyFile("/中文名.txt", out)
                assertEquals("中文文件名内容\n", out.toString("UTF-8"))
            }
        }
        Unit
    }

    @Test
    fun ntfsReadsSuperFloppy() = Unit.let {
        withVolume("ntfsnopt", FsKind.NTFS) { io, part ->
            NtfsReader(io, part.offset).use { r ->
                val out = ByteArrayOutputStream()
                r.copyFile("/hello.txt", out)
                assertEquals("hello from phonestick nopt\n", out.toString("UTF-8"))
            }
        }
        Unit
    }
}
