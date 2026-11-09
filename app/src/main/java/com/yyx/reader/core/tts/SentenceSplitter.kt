package com.yyx.reader.core.tts

/**
 * 分句器（架构文档 §5.6.2）：
 * 按句末标点切分（保留分隔符），短句并入前句，超长句在逗号处软切、必要时硬切。
 * 每个句子携带章内字符区间 [start, end)，是听书进度回写的坐标。
 */
object SentenceSplitter {

    data class Sentence(
        val index: Int,
        val start: Int,
        val end: Int,
        val text: String,
    )

    private val hardBreaks = setOf('。', '！', '？', '!', '?', '；', ';', '…', '\n')
    private val softBreaks = setOf('，', ',', '、', '：', ':')
    private const val SOFT_CUT_THRESHOLD = 120
    private const val HARD_CUT_THRESHOLD = 240
    private const val MIN_LEN = 6

    fun split(text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()

        // 1. 粗切
        val raw = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            val len = i - start
            when {
                c in hardBreaks -> {
                    raw.add(start to i + 1)
                    start = i + 1
                }
                len >= HARD_CUT_THRESHOLD -> {
                    raw.add(start to i)
                    start = i
                }
                len >= SOFT_CUT_THRESHOLD && c in softBreaks -> {
                    raw.add(start to i + 1)
                    start = i + 1
                }
            }
        }
        if (start < text.length) raw.add(start to text.length)

        // 2. 短片段并入前句
        val merged = mutableListOf<Pair<Int, Int>>()
        for (range in raw) {
            val piece = text.substring(range.first, range.second)
            if (merged.isNotEmpty() && (range.second - range.first < MIN_LEN || piece.isBlank())) {
                merged[merged.size - 1] = merged.last().first to range.second
            } else {
                merged.add(range)
            }
        }

        // 3. 产出
        return merged.mapIndexedNotNull { idx, (s, e) ->
            val piece = text.substring(s, e).trim()
            if (piece.isEmpty()) {
                null
            } else {
                Sentence(idx, s, e, piece)
            }
        }
    }
}
