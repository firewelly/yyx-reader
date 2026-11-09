package com.yyx.reader.core.domain.repository

import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.Chapter
import com.yyx.reader.core.domain.model.ProgressInfo
import com.yyx.reader.core.domain.model.ProgressSource
import com.yyx.reader.core.domain.model.BookPosition
import com.yyx.reader.core.domain.model.ScanFolder
import com.yyx.reader.core.domain.model.ShelfBook
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun observeShelf(): Flow<List<ShelfBook>>
    fun observeBook(id: Long): Flow<Book?>
    suspend fun getBook(id: Long): Book?
    suspend fun deleteBook(id: Long, deleteSourceFile: Boolean): Boolean
    suspend fun updateMetadata(id: Long, title: String, author: String?, summary: String?)
    suspend fun updateCharset(id: Long, charset: String)
    suspend fun touchLastRead(id: Long)
}

interface ChapterRepository {
    suspend fun chapters(bookId: Long): List<Chapter>
    fun observeChapters(bookId: Long): Flow<List<Chapter>>
    suspend fun chapter(bookId: Long, idx: Int): Chapter?
    /** (idx, charCount) 列表，用于全书百分比计算 */
    suspend fun charCounts(bookId: Long): List<Pair<Int, Int>>
    /** 重建章节索引（导入/重新解析），并使进度百分比缓存失效 */
    suspend fun replaceIndex(
        bookId: Long,
        drafts: List<Chapter>,
    )
}

interface ProgressRepository {
    fun observe(bookId: Long): Flow<ProgressInfo?>
    suspend fun position(bookId: Long): BookPosition?
    /** 统一进度写入：阅读与听书共用（架构文档 §5.4） */
    suspend fun update(position: BookPosition, source: ProgressSource)
    suspend fun clear(bookId: Long)
}

interface ScanFolderRepository {
    fun observeFolders(): Flow<List<ScanFolder>>
    suspend fun add(treeUri: String, displayName: String): Boolean
    suspend fun remove(folder: ScanFolder)
}

interface BookmarkRepository {
    fun observeByBook(bookId: Long): Flow<List<com.yyx.reader.core.domain.model.Bookmark>>
    suspend fun add(bookId: Long, chapterIdx: Int, charOffset: Int, excerpt: String?)
    suspend fun remove(id: Long)
    /** 该精确位置是否已有书签（页首书签模型） */
    suspend fun findAt(bookId: Long, chapterIdx: Int, charOffset: Int): com.yyx.reader.core.domain.model.Bookmark?
}
