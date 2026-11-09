package com.yyx.reader.data.repository

import com.yyx.reader.core.database.dao.BookmarkDao
import com.yyx.reader.core.database.entity.BookmarkEntity
import com.yyx.reader.core.domain.model.Bookmark
import com.yyx.reader.core.domain.repository.BookmarkRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
) : BookmarkRepository {

    override fun observeByBook(bookId: Long): Flow<List<Bookmark>> =
        bookmarkDao.observeByBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun add(bookId: Long, chapterIdx: Int, charOffset: Int, excerpt: String?) {
        bookmarkDao.insert(
            BookmarkEntity(
                bookId = bookId,
                chapterIdx = chapterIdx,
                charOffset = charOffset,
                excerpt = excerpt,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun remove(id: Long) = bookmarkDao.delete(id)

    override suspend fun findAt(bookId: Long, chapterIdx: Int, charOffset: Int): Bookmark? =
        bookmarkDao.getByPosition(bookId, chapterIdx, charOffset)?.toDomain()
}

private fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    chapterIdx = chapterIdx,
    charOffset = charOffset,
    excerpt = excerpt,
    createdAt = createdAt,
)
