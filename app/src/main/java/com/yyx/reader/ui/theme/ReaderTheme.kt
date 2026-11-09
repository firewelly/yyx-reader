package com.yyx.reader.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.yyx.reader.R

/** 阅读主题（PRD F-04-03：日间/夜间/护眼） */
enum class ReaderTheme(
    @param:StringRes val labelRes: Int,
    val bg: Color,
    val text: Color,
    /** 听书高亮句背景色（F-06-09） */
    val highlight: Color,
) {
    DAY(R.string.theme_day, Color(0xFFFFFBF0), Color(0xFF262626), Color(0x59FFD54F)),
    NIGHT(R.string.theme_night, Color(0xFF111111), Color(0xFF9E9E9E), Color(0x664FC3F9)),
    EYE(R.string.theme_eye_care, Color(0xFFC7EDCC), Color(0xFF2B2B2B), Color(0x66FFB74D)),
    ;

    companion object {
        fun of(name: String?): ReaderTheme = entries.firstOrNull { it.name == name } ?: DAY
    }
}
