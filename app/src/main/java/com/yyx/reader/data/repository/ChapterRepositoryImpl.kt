package com.yyx.reader.data.repository

import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.dao.ChapterDao
import com.yyx.reader.core.domain.model.Chapter
import com.yyx.reader.core.domain.repository.ChapterRepository
import com.yyx.reader.data.toDomain
import com.yyx.reader.data.toEntity
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ChapterRepositoryImpl @Inject constructor(
    private val db: YYXDatabase,
    private val chapterDao: ChapterDao,
) : ChapterRepository {

    /** charCounts 内存缓存：进度百分比高频写入时避免全表查询；replaceIndex 时失效 */
    private val charCountsCache = HashMap<Long, List<Pair<Int, Int>>>()

    override suspend fun chapters(bookId: Long): List<Chapter> =
        chapterDao.getByBook(bookId).map { it.toDomain() }

    override fun observeChapters(bookId: Long): Flow<List<Chapter>> =
        chapterDao.observeByBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun chapter(bookId: Long, idx: Int): Chapter? =
        chapterDao.get(bookId, idx)?.toDomain()

    override suspend fun replaceIndex(bookId: Long, drafts: List<Chapter>) {
        db.withTransaction {
            chapterDao.deleteByBook(bookId)
            chapterDao.insertAll(drafts.map { it.toEntity(bookId) })
        }
        synchronized(charCountsCache) { charCountsCache.remove(bookId) }
    }

    override suspend fun charCounts(bookId: Long): List<Pair<Int, Int>> {
        synchronized(charCountsCache) { charCountsCache[bookId] }?.let { return it }
        val rows = chapterDao.charCounts(bookId).map { it.idx to it.char_count }
        synchronized(charCountsCache) { charCountsCache[bookId] = rows }
        return rows
    }
}
