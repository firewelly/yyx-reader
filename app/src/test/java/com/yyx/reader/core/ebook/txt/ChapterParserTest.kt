package com.yyx.reader.core.ebook.txt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class ChapterParserTest {

    // ---------- 标题识别 ----------

    @Test
    fun `中文数字章节标题`() {
        assertTrue(ChapterParser.isChapterTitle("第一章 大梦初醒"))
        assertTrue(ChapterParser.isChapterTitle("第一百二十三章 危机"))
        assertTrue(ChapterParser.isChapterTitle("第12章 短兵相接"))
        assertTrue(ChapterParser.isChapterTitle("第二回"))
        assertTrue(ChapterParser.isChapterTitle("第十节"))
    }

    @Test
    fun `英文与其他格式`() {
        assertTrue(ChapterParser.isChapterTitle("Chapter 1 Begin"))
        assertTrue(ChapterParser.isChapterTitle("chapter 42"))
        assertTrue(ChapterParser.isChapterTitle("1. 开端"))
        assertTrue(ChapterParser.isChapterTitle("12、重逢"))
        assertTrue(ChapterParser.isChapterTitle("卷一 风起"))
        assertTrue(ChapterParser.isChapterTitle("序章"))
        assertTrue(ChapterParser.isChapterTitle("楔子"))
        assertTrue(ChapterParser.isChapterTitle("番外一 婚后日常"))
        assertTrue(ChapterParser.isChapterTitle("后记"))
    }

    @Test
    fun `正文行不应误判`() {
        assertFalse(ChapterParser.isChapterTitle("他翻开书，第三章的内容让他愣住了。"))
        assertFalse(ChapterParser.isChapterTitle("第三种爱情"))
        assertFalse(ChapterParser.isChapterTitle(""))
        assertFalse(ChapterParser.isChapterTitle("这是普通的一行正文内容，不含章节标记"))
    }

    @Test
    fun `超长行不算标题`() {
        val longLine = "第一章 " + "很长的正文".repeat(20)
        assertFalse(ChapterParser.isChapterTitle(longLine))
    }

    // ---------- 中文数字转换 ----------

    @Test
    fun `阿拉伯数字直转`() {
        assertEquals(42, ChapterParser.chineseToInt("42"))
        assertEquals(0, ChapterParser.chineseToInt("0"))
    }

    @Test
    fun `基础中文数字`() {
        assertEquals(1, ChapterParser.chineseToInt("一"))
        assertEquals(9, ChapterParser.chineseToInt("九"))
        assertEquals(10, ChapterParser.chineseToInt("十"))
        assertEquals(11, ChapterParser.chineseToInt("十一"))
        assertEquals(15, ChapterParser.chineseToInt("十五"))
        assertEquals(20, ChapterParser.chineseToInt("二十"))
        assertEquals(23, ChapterParser.chineseToInt("二十三"))
        assertEquals(99, ChapterParser.chineseToInt("九十九"))
    }

    @Test
    fun `百千万位`() {
        assertEquals(100, ChapterParser.chineseToInt("一百"))
        assertEquals(123, ChapterParser.chineseToInt("一百二十三"))
        assertEquals(305, ChapterParser.chineseToInt("三百零五"))
        assertEquals(1000, ChapterParser.chineseToInt("一千"))
        assertEquals(2345, ChapterParser.chineseToInt("二千三百四十五"))
        assertEquals(10000, ChapterParser.chineseToInt("一万"))
        assertEquals(12345, ChapterParser.chineseToInt("一万二千三百四十五"))
        assertEquals(100000, ChapterParser.chineseToInt("十万"))
        assertEquals(100000000, ChapterParser.chineseToInt("一亿"))
    }

    @Test
    fun `大写数字`() {
        assertEquals(1, ChapterParser.chineseToInt("壹"))
        assertEquals(10, ChapterParser.chineseToInt("拾"))
        assertEquals(23, ChapterParser.chineseToInt("贰拾叁"))
    }

    // ---------- 索引器 ----------

    private fun str(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `索引器识别章节与开篇`() {
        val content = """
            本书由 XX 整理，仅供交流学习。
            
            第一章 起点
            张三出门了。
            
            第二章 归来
            张三回来了。
        """.trimIndent()
        val chapters = TxtChapterIndexer.index(str(content), "UTF-8")

        assertEquals(3, chapters.size)
        assertEquals("开篇", chapters[0].title)
        assertEquals("第一章 起点", chapters[1].title)
        assertEquals("第二章 归来", chapters[2].title)
        // 字节偏移切片解码应还原章节内容
        val ch2Text = String(
            str(content),
            chapters[2].startOffset.toInt(),
            (chapters[2].endOffset - chapters[2].startOffset).toInt(),
            Charsets.UTF_8,
        )
        assertTrue(ch2Text.contains("第二章 归来"))
        assertTrue(ch2Text.contains("张三回来了"))
    }

    @Test
    fun `无标记全文单章`() {
        val content = "没有任何章节标记的一本书。\n第二行。\n第三行。"
        val chapters = TxtChapterIndexer.index(str(content), "UTF-8")
        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
    }

    @Test
    fun `GBK 编码章节识别`() {
        val content = "第一章 测试\n中文内容一。\n第二章 继续\n中文内容二。"
        val bytes = content.toByteArray(Charset.forName("GB18030"))
        val detected = EncodingDetector.detect(bytes)
        assertEquals("GB18030", detected)
        val chapters = TxtChapterIndexer.index(bytes, detected)
        assertEquals(2, chapters.size)
    }

    @Test
    fun `章节号单调递增过滤正文误匹配`() {
        // 段落以「第1章第0段：」开头不应成为章节
        val content = """
            这是一本测试用书的前言说明。
            第1章 测试章节1
            第1章第0段：李四坐在窗前，看着窗外的雨发呆。GB编码测试文本。
            第1章第1段：李四坐在窗前，看着窗外的雨发呆。GB编码测试文本。
            第2章 测试章节2
            第2章第0段：李四坐在窗前，看着窗外的雨发呆。GB编码测试文本。
            第3章 测试章节3
            正文内容。
        """.trimIndent()
        val chapters = TxtChapterIndexer.index(str(content), "UTF-8")
        val titles = chapters.map { it.title }
        assertEquals(listOf("开篇", "第1章 测试章节1", "第2章 测试章节2", "第3章 测试章节3"), titles)
    }

    @Test
    fun `卷号重置章节计数`() {
        val content = """
            第一卷 风起
            第一章 甲
            第二章 乙
            第二卷 云涌
            第一章 丙
        """.trimIndent()
        val chapters = TxtChapterIndexer.index(str(content), "UTF-8")
        val titles = chapters.map { it.title }
        assertEquals(5, chapters.size)
        assertTrue(titles.contains("第二卷 云涌"))
        assertTrue(titles.contains("第一章 丙"))
    }

    @Test
    fun `charCount 为解码后字符数`() {
        val content = "第一章\n汉字测试abc\n"
        val bytes = str(content)
        val chapters = TxtChapterIndexer.index(bytes, "UTF-8")
        val slice = String(
            bytes,
            chapters[0].startOffset.toInt(),
            (chapters[0].endOffset - chapters[0].startOffset).toInt(),
            Charsets.UTF_8,
        )
        assertEquals(slice.length, chapters[0].charCount)
    }
}
