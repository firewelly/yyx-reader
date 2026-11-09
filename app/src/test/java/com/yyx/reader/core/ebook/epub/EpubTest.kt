package com.yyx.reader.core.ebook.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTest {

    // ---------- HTML → 纯文本 ----------

    @Test
    fun `段落抽取保留换行`() {
        val html = """
            <html><head><title>t</title></head><body>
            <p>第一段内容。</p>
            <p>第二段内容。</p>
            <script>evil()</script>
            <div><p>嵌套段落。</p></div>
            </body></html>
        """.trimIndent()
        val text = HtmlTextExtractor.toPlainText(html)
        assertTrue(text.contains("第一段内容。"))
        assertTrue(text.contains("第二段内容。"))
        assertTrue(text.contains("嵌套段落。"))
        assertTrue(!text.contains("evil"))
        // 段落间应有换行分隔
        assertTrue(text.contains("\n"))
    }

    @Test
    fun `br 转换行`() {
        val text = HtmlTextExtractor.toPlainText("<p>上行<br/>下行</p>")
        assertTrue(text.contains("上行\n下行"))
    }

    // ---------- OPF 解析 ----------

    @Test
    fun `OPF 元数据与spine解析`() {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent().toByteArray()

        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>测试之书</dc:title>
                <dc:creator>测试作者</dc:creator>
                <dc:description>简介内容</dc:description>
              </metadata>
              <manifest>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover-img" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent().toByteArray()

        val loader: (String) -> ByteArray? = { path ->
            when (path) {
                "META-INF/container.xml" -> container
                "OEBPS/content.opf" -> opf
                else -> null
            }
        }
        val meta = EpubParser.parse(listOf("META-INF/container.xml", "OEBPS/content.opf"), loader)!!

        assertEquals("测试之书", meta.title)
        assertEquals("测试作者", meta.author)
        assertEquals("简介内容", meta.summary)
        assertEquals(listOf("OEBPS/ch1.xhtml", "OEBPS/text/ch2.xhtml"), meta.spineHrefs)
        assertEquals("OEBPS/cover.jpg", meta.coverHref)
    }

    @Test
    fun `路径解析与URL编码`() {
        assertEquals("a/b.xhtml", EpubParser.resolvePath("OEBPS", "../a/b.xhtml".let { "text/../$it" }))
        assertEquals("OEBPS/ch 1.xhtml", EpubParser.resolvePath("OEBPS", "ch%201.xhtml"))
        assertTrue(EpubParser.entryCandidates("a b.xhtml").contains("a%20b.xhtml"))
    }

    // ---------- NCX 目录 ----------

    @Test
    fun `NCX标题解析`() {
        val ncx = """
            <?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1" playOrder="1">
                  <navLabel><text>第一章 起点</text></navLabel>
                  <content src="ch1.xhtml"/>
                </navPoint>
                <navPoint id="n2" playOrder="2">
                  <navLabel><text>第二章 归来</text></navLabel>
                  <content src="text/ch2.xhtml#p1"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent().toByteArray()

        val titles = EpubToc.parseTitles(ncx)
        assertEquals(2, titles.size)
        assertEquals("第一章 起点", titles["ch1.xhtml"])
        assertEquals("第二章 归来", titles["text/ch2.xhtml"])
    }
}
