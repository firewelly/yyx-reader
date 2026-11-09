package com.yyx.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yyx.reader.feature.reader.ChapterPaginator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阅读器分页 / 行距回归测试（DEF-1 修复后的断言化版本，task-8）。
 *
 * 背景：`ChapterPaginator` 曾把 `StaticLayout.setLineSpacing(add, mult)` 的
 * lineSpacingMultiplier 传成 0f，导致行高只剩 add（≈17px）< 字形高（18sp≈47px），
 * 正文多行全部重叠、长章节被排进 1 页（DEF-1）。修复为 1.0f 后行距 ≈73px。
 *
 * 本用例锁定修复语义，防止再退化：
 * 1) mult=1.0f 时行高 >= 字形高（并保留 mult=0f 会塌陷的对照，说明为什么必须是 1.0f）；
 * 2) 产品 [ChapterPaginator] 对 80 行文本 / 1200px 高度必须切出 >= 4 页（修复前只有 2 页）；
 * 3) 真机阅读区高度（≈1900px）下 40 行文本必须 >= 2 页（修复前为 1 页）。
 */
@RunWith(AndroidJUnit4::class)
class ReaderPaginationMetricsTest {

    private companion object {
        /** 18sp @ 420dpi（Pixel 6 模拟器）≈ 47.25px，与 ReaderScreen 的 fontSizeSp 默认值一致 */
        const val TEXT_SIZE_PX = 18 * 2.625f
        const val LINE_SPACING_PX = 16
        const val LAYOUT_WIDTH = 1000
    }

    private fun paint(): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TEXT_SIZE_PX
        color = 0xFF000000.toInt()
        typeface = Typeface.SANS_SERIF
    }

    private fun buildLayout(text: CharSequence, spacingMult: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint(), LAYOUT_WIDTH)
            .setLineSpacing(LINE_SPACING_PX.toFloat(), spacingMult)
            .setIncludePad(false)
            .build()

    private fun linePitch(layout: StaticLayout): Float =
        layout.getLineBottom(layout.lineCount - 1).toFloat() / (layout.lineCount - 1)

    private fun linesText(count: Int): String =
        (1..count).joinToString("\n") { "第${it}行 内容ABCDEFG 这是独立文本行" }

    @Test
    fun fixedMultiplierKeepsLinePitchAtOrAboveGlyphHeight() {
        val progressText = linesText(20)

        val fixed = buildLayout(progressText, 1.0f)
        val fixedPitch = linePitch(fixed)
        // 对照：修复前使用的乘数 0f 会把行高压到仅 add 左右（< 字形高 → 文字重叠）
        val collapsedPitch = linePitch(buildLayout(progressText, 0f))

        println(
            "行距回归：mult=1.0f 行距=${"%.2f".format(fixedPitch)}px；" +
                "mult=0f 行距=${"%.2f".format(collapsedPitch)}px；字形高=${TEXT_SIZE_PX}px",
        )

        assertTrue(
            "修复后行距 ${"%.2f".format(fixedPitch)}px 必须 >= 字形高 ${TEXT_SIZE_PX}px，否则正文会重叠（DEF-1）",
            fixedPitch >= TEXT_SIZE_PX,
        )
        assertTrue(
            "行距 ${"%.2f".format(fixedPitch)}px 应大于纯 lineSpacingPx $LINE_SPACING_PX px（额外行距叠加生效）",
            fixedPitch > LINE_SPACING_PX,
        )
        assertTrue(
            "对照断言：mult=0f 时行距 ${"%.2f".format(collapsedPitch)}px 会小于字形高（说明乘数不能为 0f）",
            collapsedPitch < TEXT_SIZE_PX,
        )
    }

    @Test
    fun longChapterPaginatesIntoAtLeastFourPages() {
        // 80 行 × 73.44px ≈ 5875px；1200px/页应切 >=4 页（修复前乘数 0f 时只有 2 页）
        val text = linesText(80)
        val paginator = ChapterPaginator(
            text = text,
            textPaint = paint(),
            width = LAYOUT_WIDTH,
            height = 1200,
            lineSpacingPx = LINE_SPACING_PX,
        )
        println("分页回归：80 行 / 1200px -> ${paginator.pages.size} 页（期望 >=4）")

        assertTrue(
            "80 行文本在 1200px 高度只切了 ${paginator.pages.size} 页（应 >=4），分页/行距疑似退化（DEF-1）",
            paginator.pages.size >= 4,
        )
        assertEquals("分页必须覆盖全文最后字符", text.length, paginator.pages.last().last + 1)
    }

    @Test
    fun realisticReaderHeightSplitsLinesBookIntoMultiplePages() {
        // 真机阅读区高度约 1900px：40 行 × 73.44px ≈ 2937px → 至少 2 页（修复前为 1/1）
        val paginator = ChapterPaginator(
            text = linesText(40),
            textPaint = paint(),
            width = LAYOUT_WIDTH,
            height = 1900,
            lineSpacingPx = LINE_SPACING_PX,
        )
        println("分页回归：40 行 / 1900px -> ${paginator.pages.size} 页（期望 >=2）")

        assertTrue(
            "40 行文本在 1900px 高度只切了 ${paginator.pages.size} 页（应 >=2）；" +
                "修复前该文本会显示 1/1 且正文重叠（DEF-1）",
            paginator.pages.size >= 2,
        )
    }
}
