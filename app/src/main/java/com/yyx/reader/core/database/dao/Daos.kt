package com.yyx.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yyx.reader.core.database.entity.BookEntity
import com.yyx.reader.core.database.entity.BookTagEntity
import com.yyx.reader.core.database.entity.ChapterEntity
import com.yyx.reader.core.database.entity.ReadingProgressEntity
import com.yyx.reader.core.database.entity.ScanFolderEntity
import com.yyx.reader.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

data class BookShelfRow(
    @Embedded val book: BookEntity,
    val percent: Float?,
    val chapterIdx: Int?,
    val progressSource: String?,
)

@Dao
interface BookDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE file_uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): BookEntity?

    @Query("SELECT * FROM books WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): BookEntity?

    @Query(
        """
        SELECT b.*, p.percent AS percent, p.chapter_idx AS chapterIdx, p.source AS progressSource
        FROM books b LEFT JOIN reading_progress p ON p.book_id = b.id
        ORDER BY b.added_at DESC
        """,
    )
    fun observeShelf(): Flow<List<BookShelfRow>>

    @Query("UPDATE books SET title = :title, author = :author, summary = :summary, updated_at = :now WHERE id = :id")
    suspend fun updateMetadata(id: Long, title: String, author: String?, summary: String?, now: Long)

    @Query("UPDATE books SET charset = :charset, updated_at = :now WHERE id = :id")
    suspend fun updateCharset(id: Long, charset: String, now: Long)

    @Query("UPDATE books SET cover_path = :path WHERE id = :id")
    suspend fun updateCover(id: Long, path: String?)

    @Query("UPDATE books SET last_read_at = :at WHERE id = :id")
    suspend fun touchLastRead(id: Long, at: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int
}

@Dao
interface ChapterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long)

    /** 清理 book_id 悬空的孤儿章节（chapters 无数据库外键，V1.4 启动时维护） */
    @Query("DELETE FROM chapters WHERE book_id NOT IN (SELECT id FROM books)")
    suspend fun deleteOrphans(): Int

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY idx ASC")
    suspend fun getByBook(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY idx ASC")
    fun observeByBook(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId AND idx = :idx LIMIT 1")
    suspend fun get(bookId: Long, idx: Int): ChapterEntity?

    @Query("SELECT idx, char_count FROM chapters WHERE book_id = :bookId ORDER BY idx ASC")
    suspend fun charCounts(bookId: Long): List<CharCountRow>

    @Query("SELECT COALESCE(SUM(char_count), 0) FROM chapters WHERE book_id = :bookId")
    suspend fun totalChars(bookId: Long): Long

    @Query("SELECT COUNT(*) FROM chapters WHERE book_id = :bookId")
    suspend fun count(bookId: Long): Int
}

data class CharCountRow(val idx: Int, val char_count: Int)

@Dao
interface ProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId")
    suspend fun get(bookId: Long): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId")
    fun observe(bookId: Long): Flow<ReadingProgressEntity?>

    @Query("DELETE FROM reading_progress WHERE book_id = :bookId")
    suspend fun delete(bookId: Long)

    /** 清理 book_id 悬空的孤儿进度（reading_progress 无数据库外键，V1.4 启动时维护） */
    @Query("DELETE FROM reading_progress WHERE book_id NOT IN (SELECT id FROM books)")
    suspend fun deleteOrphans(): Int
}

@Dao
interface ScanFolderDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: ScanFolderEntity): Long

    @Delete
    suspend fun delete(folder: ScanFolderEntity)

    @Query("SELECT * FROM scan_folders ORDER BY added_at ASC")
    fun observeAll(): Flow<List<ScanFolderEntity>>

    @Query("SELECT * FROM scan_folders ORDER BY added_at ASC")
    suspend fun getAll(): List<ScanFolderEntity>

    @Query("UPDATE scan_folders SET last_scan_at = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("SELECT COUNT(*) FROM scan_folders")
    suspend fun count(): Int
}

@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: com.yyx.reader.core.database.entity.BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    /** 删书前显式清理书签（bookmarks 无数据库外键，V1.3 起由 deleteBook 在事务内调用） */
    @Query("DELETE FROM bookmarks WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long)

    /** 清理 book_id 悬空的孤儿书签（bookmarks 无数据库外键，V1.4 启动时维护） */
    @Query("DELETE FROM bookmarks WHERE book_id NOT IN (SELECT id FROM books)")
    suspend fun deleteOrphans(): Int

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeByBook(bookId: Long): Flow<List<com.yyx.reader.core.database.entity.BookmarkEntity>>

    @Query(
        "SELECT * FROM bookmarks WHERE book_id = :bookId AND chapter_idx = :chapterIdx " +
            "AND char_offset = :charOffset LIMIT 1",
    )
    suspend fun getByPosition(
        bookId: Long,
        chapterIdx: Int,
        charOffset: Int,
    ): com.yyx.reader.core.database.entity.BookmarkEntity?
}

/** 标签关联行（书架卡片与标签筛选用的平面结果） */
data class BookTagRow(
    val bookId: Long,
    val tagId: Long,
    val name: String,
)

@Dao
interface TagDao {

    /** 重名（唯一索引冲突）返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeTags(): Flow<List<TagEntity>>

    /** 删除标签，book_tags 由外键 ON DELETE CASCADE 清理 */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkAll(rows: List<BookTagEntity>)

    @Query("DELETE FROM book_tags WHERE tag_id = :tagId AND book_id IN (:bookIds)")
    suspend fun unlink(bookIds: List<Long>, tagId: Long)

    /** 清理任一端失效的标签关联（书籍或标签已不存在；V1.4 启动时维护，覆盖 FK 未开启时留下的历史脏数据） */
    @Query(
        "DELETE FROM book_tags WHERE book_id NOT IN (SELECT id FROM books) " +
            "OR tag_id NOT IN (SELECT id FROM tags)",
    )
    suspend fun deleteOrphanLinks(): Int

    @Query(
        "SELECT t.* FROM tags t INNER JOIN book_tags bt ON bt.tag_id = t.id " +
            "WHERE bt.book_id = :bookId ORDER BY t.name ASC",
    )
    suspend fun tagsOfBook(bookId: Long): List<TagEntity>

    @Query(
        "SELECT bt.book_id AS bookId, t.id AS tagId, t.name AS name " +
            "FROM book_tags bt INNER JOIN tags t ON t.id = bt.tag_id ORDER BY t.name ASC",
    )
    fun observeTagRows(): Flow<List<BookTagRow>>
}
