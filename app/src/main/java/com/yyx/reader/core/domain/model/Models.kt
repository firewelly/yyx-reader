package com.yyx.reader.core.domain.model

/** 电子书格式 */
enum class BookFormat { TXT, EPUB }

/** 进度来源（仅展示用，阅读与听书共用同一条进度） */
enum class ProgressSource { READ, LISTEN }

data class Book(
    val id: Long = 0,
    val title: String,
    val author: String? = null,
    val summary: String? = null,
    val format: BookFormat,
    val fileUri: String,
    val charset: String? = null,
    val coverPath: String? = null,
    val chapterCount: Int = 0,
    val wordCount: Long = 0,
    val fileMtime: Long? = null,
    val fileSize: Long? = null,
    val lastReadAt: Long? = null,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
)

data class Chapter(
    val id: Long = 0,
    val bookId: Long = 0,
    val idx: Int,
    val title: String,
    /** TXT: 起始字节偏移；EPUB: -1（用 epubHref 定位） */
    val startOffset: Long,
    val endOffset: Long,
    /** 章内字符数（进度百分比与听读同步的基准） */
    val charCount: Int,
    val epubHref: String? = null,
)

/**
 * 全应用唯一的位置表达（架构文档 §5.4）：
 * 阅读与听书读写同一条进度，charOffset 为章内字符偏移。
 */
data class BookPosition(
    val bookId: Long = 0,
    val chapterIdx: Int = 0,
    val charOffset: Int = 0,
)

data class ProgressInfo(
    val position: BookPosition,
    val percent: Float,
    val source: ProgressSource,
    val updatedAt: Long,
)

/** 书签：定位 + 自动摘录 */
data class Bookmark(
    val id: Long = 0,
    val bookId: Long = 0,
    val chapterIdx: Int = 0,
    val charOffset: Int = 0,
    val excerpt: String? = null,
    val createdAt: Long = 0,
)

data class ScanFolder(
    val id: Long = 0,
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScanAt: Long? = null,
)

/** 书架行：书籍 + 进度概览 */
data class ShelfBook(
    val book: Book,
    val percent: Float? = null,
    val chapterIdx: Int? = null,
    val progressSource: ProgressSource? = null,
)

data class ScanReport(
    val total: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val errors: List<String> = emptyList(),
)
