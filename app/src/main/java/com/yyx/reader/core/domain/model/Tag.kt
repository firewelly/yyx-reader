package com.yyx.reader.core.domain.model

/** 标签（F-13 标签与分组）：书架筛选与批量打标用 */
data class Tag(
    val id: Long = 0,
    /** 名称（唯一，最长 20 字） */
    val name: String,
)
