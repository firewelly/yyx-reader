package com.yyx.reader.core.ebook.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * EPUB 章节 HTML → 纯文本抽取（保留段落换行）。
 * 纯 JVM 实现（Jsoup），可单元测试。
 */
object HtmlTextExtractor {

    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "li", "div", "tr", "section", "article", "dd", "dt",
    )
    private val SKIP_TAGS = setOf("script", "style", "head", "noscript", "svg")

    fun toPlainText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, head, noscript, svg").remove()
        val body = doc.body() ?: return ""
        val sb = StringBuilder()
        walk(body, sb)
        return sb.toString()
            .replace(Regex("[ \\t\\x0B\\f\\r\\u00A0]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun walk(node: org.jsoup.nodes.Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> {
                val tag = node.tagName().lowercase()
                when {
                    tag in SKIP_TAGS -> return
                    tag == "br" -> sb.append('\n')
                    tag in BLOCK_TAGS -> {
                        node.childNodes().forEach { walk(it, sb) }
                        sb.append("\n\n")
                    }
                    else -> node.childNodes().forEach { walk(it, sb) }
                }
            }
        }
    }
}
