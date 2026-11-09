package com.yyx.reader.data.repository

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.dao.BookDao
import com.yyx.reader.core.database.dao.BookShelfRow
import com.yyx.reader.core.database.dao.BookmarkDao
import com.yyx.reader.core.database.dao.ChapterDao
import com.yyx.reader.core.database.dao.ProgressDao
import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.ProgressSource
import com.yyx.reader.core.domain.model.ShelfBook
import com.yyx.reader.core.domain.repository.BookRepository
import com.yyx.reader.core.ebook.epub.EpubChapterLoader
import com.yyx.reader.data.toDomain
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class BookRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: YYXDatabase,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val progressDao: ProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val epubLoader: EpubChapterLoader,
) : BookRepository {

    override fun observeShelf(): Flow<List<ShelfBook>> =
        bookDao.observeShelf().map { rows -> rows.map { it.toShelfBook() } }

    private fun BookShelfRow.toShelfBook(): ShelfBook = ShelfBook(
        book = book.toDomain(),
        percent = percent,
        chapterIdx = chapterIdx,
        progressSource = progressSource?.let {
            ProgressSource.entries.firstOrNull { s -> s.name.equals(it, true) }
        },
    )

    override suspend fun getBook(id: Long): Book? = bookDao.getById(id)?.toDomain()

    override fun observeBook(id: Long): Flow<Book?> =
        bookDao.observeById(id).map { it?.toDomain() }

    override suspend fun deleteBook(id: Long, deleteSourceFile: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val book = bookDao.getById(id) ?: return@withContext false
            // 显式清理子表：chapters / reading_progress / bookmarks 没有数据库外键（既有 schema 债），
            // 只删 books 会留孤儿行（V1.3 起在事务内先清子表；book_tags 仍由外键级联）。
            database.withTransaction {
                chapterDao.deleteByBook(id)
                progressDao.delete(id)
                bookmarkDao.deleteByBook(id)
                bookDao.delete(id)
            }
            book.coverPath?.let { path -> runCatching { File(path).delete() } }
            epubLoader.deleteCacheForBook(id)
            if (deleteSourceFile) {
                runCatching {
                    DocumentFile.fromSingleUri(context, android.net.Uri.parse(book.fileUri))?.delete()
                }.getOrDefault(false)
            }
            true
        }

    override suspend fun updateMetadata(id: Long, title: String, author: String?, summary: String?) {
        bookDao.updateMetadata(id, title.trim(), author?.trim()?.ifBlank { null }, summary?.trim()?.ifBlank { null }, System.currentTimeMillis())
    }

    override suspend fun updateCharset(id: Long, charset: String) {
        bookDao.updateCharset(id, charset, System.currentTimeMillis())
    }

    override suspend fun touchLastRead(id: Long) {
        bookDao.touchLastRead(id, System.currentTimeMillis())
    }
}
