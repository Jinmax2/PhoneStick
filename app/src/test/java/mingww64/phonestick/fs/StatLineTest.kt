package mingww64.phonestick.fs

import mingww64.phonestick.ImageFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析层的小工具测试：不依赖镜像，纯字符串逻辑。
 * 这些格式在真机上是"能列目录但名字带 \t"这类隐蔽 bug 的来源，所以值得盯住。
 */
class StatLineTest {

    @Test
    fun parsesToyboxStyleTabSeparated() {
        val e = StatLine.toEntry("81ff\t22\t1794709974\thello.txt")
        assertEquals("hello.txt", e?.name)
        assertEquals(22L, e?.size)
        assertEquals(1794709974L, e?.mtime)
        assertEquals(false, e?.isDir)
    }

    @Test
    fun parsesGnuStyleLiteralBackslashT() {
        // GNU stat 的 -c 不解释 \t，原样输出
        val e = StatLine.toEntry("81ff\\t3145728\\t1794709974\\tbig.bin")
        assertEquals("big.bin", e?.name)
        assertEquals(3145728L, e?.size)
        assertEquals(false, e?.isDir)
    }

    @Test
    fun detectsDirectoryByModeBits() {
        val dir = StatLine.toEntry("41ff\\t512\\t1794709974\\tdir1")
        assertEquals(true, dir?.isDir)
        val file = StatLine.toEntry("81a4\\t10\\t0\\treadme")
        assertEquals(false, file?.isDir)
    }

    @Test
    fun stripsFindDotSlashPrefix() {
        val e = StatLine.toEntry("81ff\\t2\\t0\\t./.hidden")
        assertEquals(".hidden", e?.name)
    }

    @Test
    fun keepsNamesWithSpacesAndChinese() {
        val e = StatLine.toEntry("41ff\\t512\\t0\\t./子 目录")
        assertEquals("子 目录", e?.name)
        assertTrue(e!!.isDir)
    }

    @Test
    fun keepsLiteralBackslashTInsideName() {
        // 名字里本身就有 \t 两个字面字符，不应被再次切分
        val e = StatLine.toEntry("81ff\\t3\\t0\\ta\\tb.txt")
        assertEquals("a\\tb.txt", e?.name)
    }

    @Test
    fun rejectsGarbageLines() {
        assertNull(StatLine.toEntry(""))
        assertNull(StatLine.toEntry("__PS_END__"))
        assertNull(StatLine.toEntry("stat: cannot stat 'x': No such file"))
        assertNull(StatLine.toEntry("81ff\\t22\\t"))
    }

    @Test
    fun pathHelpersNormalizeCorrectly() {
        assertEquals("/", ImageFs.normalize(""))
        assertEquals("/", ImageFs.normalize("/"))
        assertEquals("/a/b", ImageFs.normalize("a//b/"))
        assertEquals("/a/b", ImageFs.child("/a", "b"))
        assertEquals("/b", ImageFs.child("/", "b"))
        assertEquals("/a", ImageFs.parent("/a/b"))
        assertEquals("/", ImageFs.parent("/a"))
        assertEquals("/", ImageFs.parent("/"))
        assertEquals("b.txt", ImageFs.nameOf("/a/b.txt"))
    }
}
