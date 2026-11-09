package com.yyx.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books", indices = [Index(value = ["file_uri"], unique = true)])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    val summary: String? = null,
    /** 'txt' | 'epub' */
    val format: String,
    @ColumnInfo(name = "file_uri") val fileUri: String,
    val charset: String? = null,
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int = 0,
    @ColumnInfo(name = "word_count") val wordCount: Long = 0,
    @ColumnInfo(name = "file_mtime") val fileMtime: Long? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "chapters",
    indices = [Index(value = ["book_id", "idx"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    /** 0-based 章节序号 */
    @ColumnInfo(name = "idx") val idx: Int,
    val title: String,
    /** TXT: 字节偏移；EPUB: -1 */
    @ColumnInfo(name = "start_offset") val startOffset: Long,
    @ColumnInfo(name = "end_offset") val endOffset: Long,
    @ColumnInfo(name = "char_count") val charCount: Int,
    @ColumnInfo(name = "epub_href") val epubHref: String? = null,
)

/**
 * 统一进度：每书一行，阅读与听书共同读写（架构文档 §4.1）。
 */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "chapter_idx") val chapterIdx: Int,
    /** 章内字符偏移（与排版无关，支撑听读同步） */
    @ColumnInfo(name = "char_offset") val charOffset: Int,
    val percent: Float,
    /** 'read' | 'listen' */
    val source: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["book_id"])])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "chapter_idx") val chapterIdx: Int,
    @ColumnInfo(name = "char_offset") val charOffset: Int,
    val excerpt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 标签（F-13 标签与分组）：名称唯一，删除标签时由外键级联清理关联 */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

/** 书籍-标签关联：复合主键防止重复打标，书籍/标签删除时级联清理 */
@Entity(
    tableName = "book_tags",
    primaryKeys = ["book_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // 两个外键子列都建索引，避免 Room 全表扫描警告
    indices = [Index(value = ["book_id"]), Index(value = ["tag_id"])],
)
data class BookTagEntity(
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

@Entity(tableName = "scan_folders", indices = [Index(value = ["tree_uri"], unique = true)])
data class ScanFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long? = null,
)
