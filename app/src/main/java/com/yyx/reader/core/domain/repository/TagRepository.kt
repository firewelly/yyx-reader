package com.yyx.reader.core.domain.repository

import com.yyx.reader.core.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/** 标签仓库（F-13）：书架筛选 + 批量打标 */
interface TagRepository {

    fun observeAll(): Flow<List<Tag>>

    /** bookId → 该书的标签列表（书架卡片与筛选共用） */
    fun observeTagMap(): Flow<Map<Long, List<Tag>>>

    /** 新建标签：名称 trim/去空/截断 20 字；重名返回 null */
    suspend fun create(name: String): Long?

    /** 删除标签（book_tags 关联由外键级联清理），书籍本身不受影响 */
    suspend fun delete(id: Long)

    suspend fun addToBooks(bookIds: Collection<Long>, tagId: Long)

    suspend fun removeFromBooks(bookIds: Collection<Long>, tagId: Long)

    suspend fun tagsOf(bookId: Long): List<Tag>
}
