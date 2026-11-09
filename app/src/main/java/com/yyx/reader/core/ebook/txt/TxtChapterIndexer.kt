package com.yyx.reader.core.ebook.txt

import java.nio.charset.Charset

/**
 * TXT 章节索引器：扫描全文一次，按行匹配章节标题，产出字节偏移索引。
 *
 * 设计要点（架构文档 §5.2）：
 * - 章节以「字节偏移」入库，阅读/听书时按需切片解码，大文件不整本常驻
 * - LF(0x0A) 在 UTF-8/GBK/Big5 的多字节序列中不可能是尾字节，可安全按原始字节分行；
 *   UTF-16 系列无此保证，单独走「整本解码 + char*2 字节映射」路径
 * - char_count 为解码后真实字符数，是进度百分比与听读同步的基准
 */
object TxtChapterIndexer {

    data class IndexedChapter(
        val idx: Int,
        val title: String,
        val startOffset: Long,
        val endOffset: Long,
        val charCount: Int,
    )

    fun index(bytes: ByteArray, charsetName: String): List<IndexedChapter> =
        if (charsetName.startsWith("UTF-16")) indexUtf16(bytes, charsetName)
        else indexByteWise(bytes, charsetName)

    /**
     * 章节号单调递增过滤：
     * 正文段落可能以「第1章第0段：…」开头并命中标题正则，但其序号不递增；
     * 卷号（第X卷/卷X）出现时重置计数，允许其下章节从 1 重新开始。
     * 返回保留的标题下标。
     */
    private fun ascendingIndices(titles: List<String>): List<Int> {
        val kept = mutableListOf<Int>()
        var lastNum = 0
        for ((i, title) in titles.withIndex()) {
            val num = ChapterParser.chapterNumberOf(title)
            if (num == null) {
                kept.add(i) // 序章/楔子/番外等无编号标题直接保留
            } else if (ChapterParser.isVolumeTitle(title)) {
                kept.add(i)
                lastNum = 0
            } else if (num > lastNum) {
                kept.add(i)
                lastNum = num
            }
        }
        return kept
    }

    // ---------- 单字节系（UTF-8 / GB18030 / Big5 等） ----------

    private fun indexByteWise(bytes: ByteArray, charsetName: String): List<IndexedChapter> {
        val cs = EncodingDetector.charsetFor(charsetName)
        val titles = mutableListOf<String>()
        val starts = mutableListOf<Int>()

        var lineStart = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                considerLine(bytes, lineStart, i, cs, titles, starts)
                lineStart = i + 1
            }
        }
        if (lineStart < bytes.size) {
            considerLine(bytes, lineStart, bytes.size, cs, titles, starts)
        }
        // 章节号单调性过滤（正文段落误匹配防护）
        val kept = ascendingIndices(titles)
        return assemble(bytes, kept.map { titles[it] }, kept.map { starts[it] }, cs)
    }

    private fun considerLine(
        bytes: ByteArray,
        start: Int,
        end: Int,
        cs: Charset,
        titles: MutableList<String>,
        starts: MutableList<Int>,
    ) {
        var e = end
        if (e > start && bytes[e - 1] == '\r'.code.toByte()) e--
        if (e <= start) return
        val line = String(bytes, start, e - start, cs)
        if (ChapterParser.isChapterTitle(line)) {
            titles.add(line.trim())
            starts.add(start)
        }
    }

    // ---------- UTF-16 系列（2 字节定长） ----------

    private fun indexUtf16(bytes: ByteArray, charsetName: String): List<IndexedChapter> {
        val cs = EncodingDetector.charsetFor(charsetName)
        val bomLen = if (bytes.size >= 2) 2 else 0
        val text = String(bytes, bomLen, bytes.size - bomLen, cs)

        val titles = mutableListOf<String>()
        val charStarts = mutableListOf<Int>()
        var lineStart = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                considerLineUtf16(text, lineStart, i, titles, charStarts)
                lineStart = i + 1
            }
            i++
        }
        if (lineStart < text.length) {
            considerLineUtf16(text, lineStart, text.length, titles, charStarts)
        }

        val kept = ascendingIndices(titles)
        val fTitles = kept.map { titles[it] }
        val fStarts = kept.map { charStarts[it] }

        val chapters = mutableListOf<IndexedChapter>()
        var idx = 0
        if (fStarts.isEmpty()) {
            val clean = text.trimStart('\uFEFF')
            if (clean.isNotBlank()) {
                chapters.add(IndexedChapter(0, "全文", bomLen.toLong(), bytes.size.toLong(), clean.length))
            }
            return chapters
        }
        if (fStarts[0] > 0) {
            val preface = text.substring(0, fStarts[0])
            if (preface.isNotBlank()) {
                chapters.add(
                    IndexedChapter(idx++, "开篇", bomLen.toLong(), bomLen + fStarts[0] * 2L, preface.length),
                )
            }
        }
        for (k in fStarts.indices) {
            val charStart = fStarts[k]
            val charEnd = if (k + 1 < fStarts.size) fStarts[k + 1] else text.length
            chapters.add(
                IndexedChapter(
                    idx++,
                    fTitles[k],
                    bomLen + charStart * 2L,
                    bomLen + charEnd * 2L,
                    charEnd - charStart,
                ),
            )
        }
        return chapters
    }

    private fun considerLineUtf16(
        text: String,
        start: Int,
        end: Int,
        titles: MutableList<String>,
        starts: MutableList<Int>,
    ) {
        var e = end
        if (e > start && text[e - 1] == '\r') e--
        if (e <= start) return
        val line = text.substring(start, e)
        if (ChapterParser.isChapterTitle(line)) {
            titles.add(line.trim())
            starts.add(start)
        }
    }

    // ---------- 组装章节（共用） ----------

    private fun assemble(
        bytes: ByteArray,
        titles: List<String>,
        starts: List<Int>,
        cs: Charset,
    ): List<IndexedChapter> {
        if (starts.isEmpty()) {
            if (bytes.isEmpty()) return emptyList()
            val whole = decode(bytes, 0, bytes.size, cs)
            return if (whole.isBlank()) {
                emptyList()
            } else {
                listOf(IndexedChapter(0, "全文", 0, bytes.size.toLong(), whole.length))
            }
        }

        val chapters = mutableListOf<IndexedChapter>()
        var idx = 0

        // 首个标题前的内容 → 「开篇」
        if (starts[0] > 0) {
            val preface = decode(bytes, 0, starts[0], cs)
            if (preface.isNotBlank()) {
                chapters.add(IndexedChapter(idx++, "开篇", 0, starts[0].toLong(), preface.length))
            }
        }

        for (k in starts.indices) {
            val start = starts[k]
            val end = if (k + 1 < starts.size) starts[k + 1] else bytes.size
            val charCount = decode(bytes, start, end, cs).length
            chapters.add(IndexedChapter(idx++, titles[k], start.toLong(), end.toLong(), charCount))
        }
        return chapters
    }

    private fun decode(bytes: ByteArray, start: Int, end: Int, cs: Charset): String {
        val raw = String(bytes, start, end - start, cs)
        return if (start == 0) raw.trimStart('\uFEFF') else raw
    }
}
