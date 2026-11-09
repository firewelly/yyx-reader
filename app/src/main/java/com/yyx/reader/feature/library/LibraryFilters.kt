package com.yyx.reader.feature.library

import com.yyx.reader.core.domain.model.ShelfBook
import com.yyx.reader.core.domain.model.Tag

/** 书架排序方式（从 ViewModel 抽出，便于筛选纯函数单测）；展示文案由界面层映射资源 */
enum class Sort { RECENT, ADDED, TITLE, AUTHOR }

/**
 * 书架过滤 + 排序纯函数：查询词与标签为 AND 关系，最后按排序方式输出。
 * @param tagMap bookId → 标签（TagRepository.observeTagMap）
 * @param filterTagId null 表示「全部」
 */
fun filterShelf(
    books: List<ShelfBook>,
    query: String,
    sort: Sort,
    filterTagId: Long? = null,
    tagMap: Map<Long, List<Tag>> = emptyMap(),
): List<ShelfBook> {
    var list = books
    if (query.isNotBlank()) {
        list = list.filter { shelf ->
            shelf.book.title.contains(query, ignoreCase = true) ||
                shelf.book.author?.contains(query, ignoreCase = true) == true
        }
    }
    if (filterTagId != null) {
        list = list.filter { shelf ->
            tagMap[shelf.book.id]?.any { tag -> tag.id == filterTagId } == true
        }
    }
    return when (sort) {
        Sort.RECENT -> list.sortedByDescending { it.book.lastReadAt ?: 0L }
        Sort.ADDED -> list.sortedByDescending { it.book.addedAt }
        Sort.TITLE -> list.sortedBy { it.book.title }
        Sort.AUTHOR -> list.sortedBy { it.book.author ?: "￿" }
    }
}

/** 批量打标的初始勾选集合：所选书籍共同拥有的标签（交集），取消勾选即移除 */
fun commonTagIds(bookIds: Set<Long>, tagMap: Map<Long, List<Tag>>): Set<Long> {
    if (bookIds.isEmpty()) return emptySet()
    return bookIds
        .map { bookId -> tagMap[bookId].orEmpty().map { it.id }.toSet() }
        .reduce { acc, ids -> acc intersect ids }
}

/** 卡片上的标签摘要：最多 [maxVisible] 个，剩余以“+n”标记；无标签返回 null */
fun tagSummary(tags: List<Tag>, maxVisible: Int = 2): String? {
    if (tags.isEmpty()) return null
    val visible = tags.take(maxVisible).joinToString(" · ") { it.name }
    val overflow = tags.size - maxVisible
    return if (overflow > 0) "$visible +$overflow" else visible
}
