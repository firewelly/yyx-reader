package com.yyx.reader.core.ebook.txt

/**
 * TXT 章节标题解析器。
 * 正则优先级表与中文数字转换算法移植自 novelmgt `chapter_parser.dart`，
 * 新增：序章/前言/楔子/番外等常见标记；行长度上限防误匹配。
 * 纯 JVM 实现，可单元测试。
 */
object ChapterParser {

    /** 章节标题正则（按优先级排列）；标题内容可空（如只有「第三章」） */
    private val chapterPatterns = listOf(
        Regex("^第[一二三四五六七八九十百千万零〇壹贰叁肆伍陆柒捌玖拾佰仟萬億\\d]+章(\\s*.+)?"),
        Regex("^第[一二三四五六七八九十百千万零〇壹贰叁肆伍陆柒捌玖拾佰仟萬億\\d]+节(\\s*.+)?"),
        Regex("^第[一二三四五六七八九十百千万零〇壹贰叁肆伍陆柒捌玖拾佰仟萬億\\d]+回(\\s*.+)?"),
        Regex("^第[一二三四五六七八九十百千万零〇壹贰叁肆伍陆柒捌玖拾佰仟萬億\\d]+卷(\\s*.+)?"),
        Regex("^卷[一二三四五六七八九十百千万零〇壹贰叁肆伍陆柒捌玖拾佰仟萬億\\d]+(\\s*.+)?"),
        Regex("^[Cc]hapter\\s+\\d+.*"),
        Regex("^\\d+[、.．]\\s*\\S+"),
        Regex("^(序章|序言|前言|楔子|后记|尾声|终章)(\\s*.{0,30})?$"),
        Regex("^番外[一二三四五六七八九十百千\\d]*(\\s*.{0,30})?$"),
    )

    /** 标题行长度上限：超过视为正文误匹配 */
    private const val MAX_TITLE_LENGTH = 64

    private val NUM_MAP = mapOf(        '零' to 0, '〇' to 0,
        '一' to 1, '壹' to 1,
        '二' to 2, '贰' to 2,
        '三' to 3, '叁' to 3,
        '四' to 4, '肆' to 4,
        '五' to 5, '伍' to 5,
        '六' to 6, '陆' to 6,
        '七' to 7, '柒' to 7,
        '八' to 8, '捌' to 8,
        '九' to 9, '玖' to 9,
        '十' to 10, '拾' to 10,
        '百' to 100, '佰' to 100,
        '千' to 1000, '仟' to 1000,
        '万' to 10000, '萬' to 10000,
        '亿' to 100000000, '億' to 100000000,
    )

    fun isChapterTitle(rawLine: String): Boolean {
        val line = rawLine.trim()
        if (line.isEmpty() || line.length > MAX_TITLE_LENGTH) return false
        return chapterPatterns.any { it.matches(line) }
    }

    private val numberPattern = Regex("^第([一二三四五六七八九十百千万零〇壹贰叁肆伍陆柒捌玖拾佰仟萬億\\d]+)[章节回卷]")

    /**
     * 提取「第X章/节/回/卷」中的序号（中文数字或阿拉伯数字）。
     * 用于章节号单调性校验：正文段落若以「第1章第0段：…」开头，
     * 其序号不递增，将被过滤（卷号出现时重置计数）。
     */
    fun chapterNumberOf(title: String): Int? {
        val match = numberPattern.find(title.trim()) ?: return null
        return chineseToInt(match.groupValues[1])
    }

    fun isVolumeTitle(title: String): Boolean =
        title.trim().startsWith("卷") || Regex("^第[一二三四五六七八九十百千万零〇壹贰叁肆伍陆柒捌玖拾佰仟萬億\\d]+卷").containsMatchIn(title.trim())

    /**
     * 中文数字转整数（从左往右段式解析）。
     * 相比 novelmgt 的右→左算法，修复了「十万」「千亿」等大单位开头/嵌套的场景。
     */
    fun chineseToInt(chinese: String): Int {
        if (chinese.isEmpty()) return 0
        chinese.toIntOrNull()?.let { return it }

        var total = 0     // 万/亿已结算部分
        var section = 0   // 当前万/亿段内数值
        var digit = 0     // 最近读到的数字

        for (ch in chinese) {
            val v = NUM_MAP[ch] ?: continue
            if (v < 10) {
                digit = v
            } else if (v >= 10000) {
                // 万/亿：整段结算进 total；「万/亿」单独出现时倍率取 1
                val seg = section + digit
                section = (if (seg == 0) 1 else seg) * v
                total += section
                section = 0
                digit = 0
            } else {
                // 十/百/千；「十」开头（如 十五）时基数取 1
                val d = if (digit == 0 && section == 0 && v == 10) 1 else digit
                section += d * v
                digit = 0
            }
        }
        return total + section + digit
    }
}
