package com.yyx.reader.data.repository

import com.yyx.reader.core.database.dao.BookDao
import com.yyx.reader.core.database.dao.ProgressDao
import com.yyx.reader.core.database.entity.ReadingProgressEntity
import com.yyx.reader.core.domain.model.BookPosition
import com.yyx.reader.core.domain.model.ProgressInfo
import com.yyx.reader.core.domain.model.ProgressSource
import com.yyx.reader.core.domain.repository.ProgressRepository
import com.yyx.reader.data.toDomain
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 统一进度仓库（架构文档 §5.4）：
 * - 阅读与听书读写同一条记录，source 仅用于展示
 * - 百分比 = (当前章之前总字数 + 章内偏移) / 全书字数
 */
@Singleton
class ProgressRepositoryImpl @Inject constructor(
    private val progressDao: ProgressDao,
    private val bookDao: BookDao,
    private val chapterRepository: ChapterRepositoryImpl,
) : ProgressRepository {

    override fun observe(bookId: Long): Flow<ProgressInfo?> =
        progressDao.observe(bookId).map { it?.toDomain() }

    override suspend fun position(bookId: Long): BookPosition? =
        progressDao.get(bookId)?.let {
            BookPosition(bookId = it.bookId, chapterIdx = it.chapterIdx, charOffset = it.charOffset)
        }

    override suspend fun update(position: BookPosition, source: ProgressSource) {
        if (position.bookId <= 0) return
        val counts = chapterRepository.charCounts(position.bookId)
        val total = counts.sumOf { it.second }.toLong().coerceAtLeast(1)
        val before = counts
            .filter { it.first < position.chapterIdx }
            .sumOf { it.second }
        val percent = ((before + position.charOffset).toDouble() / total).toFloat().coerceIn(0f, 1f)

        progressDao.upsert(
            ReadingProgressEntity(
                bookId = position.bookId,
                chapterIdx = position.chapterIdx,
                charOffset = position.charOffset,
                percent = percent,
                source = source.name.lowercase(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        bookDao.touchLastRead(position.bookId, System.currentTimeMillis())
    }

    override suspend fun clear(bookId: Long) {
        progressDao.delete(bookId)
    }
}
