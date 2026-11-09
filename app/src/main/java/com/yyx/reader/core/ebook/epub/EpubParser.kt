package com.yyx.reader.core.ebook.epub

import java.net.URLDecoder
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * EPUB 元数据与 spine 解析（OPF 2.0/3.0 常见形态，容错处理）。
 * 纯 JVM 实现，可单元测试：parse(entryNames, loader)。
 */
object EpubParser {

    data class Meta(
        val title: String?,
        val author: String?,
        val summary: String?,
        /** spine 顺序的章节 zip 路径 */
        val spineHrefs: List<String>,
        /** 封面图片 zip 路径（可空） */
        val coverHref: String?,
    )

    private val DC_NS = "http://purl.org/dc/elements/1.1/"

    fun parse(entryNames: List<String>, loader: (String) -> ByteArray?): Meta? {
        // 1. container.xml → OPF 路径
        val containerBytes = loader("META-INF/container.xml")
            ?: entryNames.firstOrNull { it.equals("META-INF/container.xml", true) }?.let { loader(it) }
            ?: return null
        val containerDom = dom(containerBytes) ?: return null
        val opfPath = containerDom.getElementsByTagName("rootfile").toList()
            .mapNotNull { (it as? Element)?.getAttribute("full-path")?.takeIf { p -> p.isNotBlank() } }
            .firstOrNull()
            ?: entryNames.firstOrNull { it.endsWith(".opf", true) }
            ?: return null

        // 2. OPF
        val opfBytes = loader(opfPath) ?: return null
        val opf = dom(opfBytes) ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")

        // metadata
        val title = dc(opf, "title")
        val author = dc(opf, "creator")
        val summary = dc(opf, "description")

        // manifest: id → (href, properties)
        data class Item(val href: String, val properties: String)
        val items = mutableMapOf<String, Item>()
        opf.getElementsByTagName("item").toList().forEach { node ->
            val el = node as? Element ?: return@forEach
            val id = el.getAttribute("id")
            val href = el.getAttribute("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                items[id] = Item(href, el.getAttribute("properties") ?: "")
            }
        }

        // spine
        val spineHrefs = mutableListOf<String>()
        opf.getElementsByTagName("itemref").toList().forEach { node ->
            val el = node as? Element ?: return@forEach
            val idref = el.getAttribute("idref")
            items[idref]?.let { spineHrefs.add(resolvePath(opfDir, it.href)) }
        }

        // cover：EPUB3 properties=cover-image 优先；EPUB2 meta name=cover 兜底
        val coverHref = items.values.firstOrNull { it.properties.split(Regex("\\s+")).contains("cover-image") }
            ?.let { resolvePath(opfDir, it.href) }
            ?: opf.getElementsByTagName("meta").toList()
                .filterIsInstance<Element>()
                .firstOrNull { it.getAttribute("name") == "cover" }
                ?.getAttribute("content")
                ?.let { items[it]?.href }
                ?.let { resolvePath(opfDir, it) }

        if (spineHrefs.isEmpty()) return null
        return Meta(title, author, summary, spineHrefs, coverHref)
    }

    // ---------- 路径工具 ----------

    /** 相对 OPF 目录解析 zip 内路径，归一化 ../ 与 URL 编码 */
    fun resolvePath(baseDir: String, href: String): String {
        val decoded = try {
            URLDecoder.decode(href.trim(), "UTF-8")
        } catch (_: Exception) {
            href.trim()
        }
        val combined = if (baseDir.isBlank()) decoded else "$baseDir/$decoded"
        val parts = mutableListOf<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    /** zip 条目名匹配候选：EPUB href 是 URL 编码的，zip 内可能存原始或编码形态 */
    fun entryCandidates(path: String): List<String> {
        val result = LinkedHashSet<String>()
        result.add(path)
        result.add(path.replace(" ", "%20"))
        try {
            result.add(URLEncoder.encode(path, "UTF-8").replace("+", "%20"))
        } catch (_: Exception) {
        }
        result.add(path.replace("%20", " "))
        return result.toList()
    }

    // ---------- XML 工具 ----------

    private fun dom(bytes: ByteArray): org.w3c.dom.Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // 禁用外部实体（XXE 防护；本地书籍亦不例外）
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.newDocumentBuilder().parse(java.io.ByteArrayInputStream(bytes))
    } catch (_: Exception) {
        null
    }

    private fun dc(doc: org.w3c.dom.Document, local: String): String? =
        doc.getElementsByTagNameNS(DC_NS, local).toList()
            .filterIsInstance<Element>()
            .firstOrNull { it.textContent.isNotBlank() }
            ?.textContent?.trim()
            ?: doc.getElementsByTagName("dc:$local").toList()
                .filterIsInstance<Element>()
                .firstOrNull { it.textContent.isNotBlank() }
                ?.textContent?.trim()

    private fun org.w3c.dom.NodeList.toList(): List<Node> =
        (0 until length).map { item(it) }
}
