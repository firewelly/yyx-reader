package com.yyx.reader.data.repository

import com.yyx.reader.core.database.dao.TagDao
import com.yyx.reader.core.database.entity.BookTagEntity
import com.yyx.reader.core.database.entity.TagEntity
import com.yyx.reader.core.domain.model.Tag
import com.yyx.reader.core.domain.repository.TagRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class TagRepositoryImpl @Inject constructor(
    private val tagDao: TagDao,
) : TagRepository {

    override fun observeAll(): Flow<List<Tag>> =
        tagDao.observeTags().map { list -> list.map { it.toDomain() } }

    override fun observeTagMap(): Flow<Map<Long, List<Tag>>> =
        tagDao.observeTagRows().map { rows ->
            // 平面结果按书籍分组；同名标签已由唯一索引保证不重复
            rows.groupBy({ it.bookId }, { Tag(id = it.tagId, name = it.name) })
        }

    override suspend fun create(name: String): Long? {
        val normalized = name.trim().take(MAX_NAME_LENGTH)
        if (normalized.isEmpty()) return null
        // IGNORE 冲突时返回 -1，即重名
        val id = tagDao.insertTag(TagEntity(name = normalized))
        return if (id > 0) id else null
    }

    override suspend fun delete(id: Long) = tagDao.deleteTag(id)

    override suspend fun addToBooks(bookIds: Collection<Long>, tagId: Long) {
        if (bookIds.isEmpty()) return
        tagDao.linkAll(bookIds.map { BookTagEntity(bookId = it, tagId = tagId) })
    }

    override suspend fun removeFromBooks(bookIds: Collection<Long>, tagId: Long) {
        if (bookIds.isEmpty()) return
        tagDao.unlink(bookIds.toList(), tagId)
    }

    override suspend fun tagsOf(bookId: Long): List<Tag> =
        tagDao.tagsOfBook(bookId).map { it.toDomain() }

    private fun TagEntity.toDomain(): Tag = Tag(id = id, name = name)

    companion object {
        /** 标签名长度上限，避免书架筛选 chip 撑破布局 */
        private const val MAX_NAME_LENGTH = 20
    }
}
