package com.yyx.reader.feature.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 章节分页引擎（架构文档 §5.5）：
 * StaticLayout 排版整章 → 按行累积高度切页 → 记录每页字符区间。
 * 进度 ↔ 页面的换算全部基于字符偏移，与听书共用同一坐标（§5.4）。
 * text 支持 CharSequence（Spannable）：听书高亮句经 BackgroundColorSpan 注入（F-06-09）。
 */
class ChapterPaginator(
    private val text: CharSequence,
    textPaint: TextPaint,
    private val width: Int,
    private val height: Int,
    lineSpacingPx: Int,
) {
    private val layout: StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, textPaint, width.coerceAtLeast(1))
        // 行高 = 字形高 × 行距乘数 + 额外行距：乘数必须为 1.0f，
        // 若为 0f 行高只剩 lineSpacingPx（默认 16px < 字形高），正文多行会重叠不可读（DEF-1）。
        .setLineSpacing(lineSpacingPx.toFloat(), 1.0f)
        .setIncludePad(false)
        .build()

    /** 每页的字符区间 [start, end) */
    val pages: List<IntRange> = buildList {
        if (text.isEmpty()) {
            add(IntRange(0, 0))
            return@buildList
        }
        var pageStartChar = 0
        var pageTop = 0
        for (line in 0 until layout.lineCount) {
            val lineBottom = layout.getLineBottom(line)
            if (lineBottom - pageTop > height && line > 0) {
                val pageEnd = layout.getLineStart(line)
                add(pageStartChar until pageEnd)
                pageStartChar = pageEnd
                pageTop = layout.getLineTop(line)
            }
        }
        add(pageStartChar until text.length)
    }

    /** 字符偏移所在页码 */
    fun pageOf(charOffset: Int): Int =
        pages.indexOfFirst { charOffset in it }.takeIf { it >= 0 }
            ?: if (charOffset <= 0) 0 else pages.size - 1

    /** 绘制指定页（调用方已完成平移到内容区左上角） */
    fun draw(canvas: Canvas, page: Int) {
        if (page !in pages.indices) return
        val range = pages[page]
        val firstLine = layout.getLineForOffset(range.first)
        val top = layout.getLineTop(firstLine)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(0f, -top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    companion object {
        /** 便捷构造：sp 字号 + dp 行距 + 颜色 */
        fun build(
            text: CharSequence,
            textSizePx: Float,
            lineSpacingPx: Int,
            colorArgb: Int,
            width: Int,
            height: Int,
        ): ChapterPaginator {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSizePx
                this.color = colorArgb
                typeface = Typeface.SANS_SERIF
            }
            return ChapterPaginator(text, paint, width, height, lineSpacingPx)
        }
    }
}
