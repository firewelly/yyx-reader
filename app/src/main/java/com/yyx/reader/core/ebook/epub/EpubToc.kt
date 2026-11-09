package com.yyx.reader.core.ebook.epub

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * EPUB 目录（NCX toc.ncx）标题解析：src(无 fragment) → 章节标题。
 * EPUB3 nav.xhtml 暂不处理，缺 NCX 时回退「第 N 章」命名。
 */
object EpubToc {

    fun parseTitles(ncxBytes: ByteArray): Map<String, String> {
        val dom = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.newDocumentBuilder().parse(ncxBytes.inputStream())
        } catch (_: Exception) {
            return emptyMap()
        }
        val ncxDir = ""
        val result = mutableMapOf<String, String>()
        dom.getElementsByTagName("navPoint").toList().forEach { node ->
            val navPoint = node as? Element ?: return@forEach
            val label = navPoint.getElementsByTagName("navLabel").toList()
                .filterIsInstance<Element>()
                .firstOrNull()
                ?.textContent?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.takeIf { it.isNotBlank() } ?: return@forEach
            val src = navPoint.getElementsByTagName("content").toList()
                .filterIsInstance<Element>()
                .firstOrNull()
                ?.getAttribute("src")
                ?.takeIf { it.isNotBlank() } ?: return@forEach
            val base = src.substringBefore('#')
            if (base.isNotBlank()) {
                result.putIfAbsent(EpubParser.resolvePath(ncxDir, base), label)
            }
        }
        return result
    }

    private fun org.w3c.dom.NodeList.toList(): List<org.w3c.dom.Node> =
        (0 until length).map { item(it) }
}
