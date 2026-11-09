package com.yyx.reader.feature.library

import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.BookFormat
import com.yyx.reader.core.domain.model.ShelfBook
import com.yyx.reader.core.domain.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-13 书架筛选纯函数单测：查询/标签/排序组合与边界 */
class LibraryFiltersTest {

    private fun shelf(
        id: Long,
        title: String,
        author: String? = null,
        lastReadAt: Long? = null,
        addedAt: Long = 0,
    ): ShelfBook = ShelfBook(
        book = Book(
            id = id,
            title = title,
            author = author,
            format = BookFormat.TXT,
            fileUri = "content://test/$id",
            lastReadAt = lastReadAt,
            addedAt = addedAt,
        ),
    )

    // 三本书固定语料：玄幻 / 都市两个标签，作者与时间各不相同
    private val b1 = shelf(1, "斗破苍穹", "天蚕土豆", lastReadAt = 300, addedAt = 1)
    private val b2 = shelf(2, "诡秘之主", "爱潜水的乌贼", lastReadAt = 500, addedAt = 3)
    private val b3 = shelf(3, "完美世界", null, lastReadAt = null, addedAt = 2)
    private val books = listOf(b1, b2, b3)

    private val fantasy = Tag(1, "玄幻")
    private val urban = Tag(2, "都市")
    private val tagMap = mapOf(
        1L to listOf(fantasy),
        2L to listOf(fantasy, urban),
        3L to emptyList(),
    )

    @Test
    fun `查询词命中书名与作者`() {
        assertEquals(listOf(1L), filterShelf(books, "斗破", Sort.TITLE).map { it.book.id })
        // 作者命中（大小写不敏感对中文无影响，验证字段匹配分支）
        assertEquals(listOf(1L), filterShelf(books, "天蚕", Sort.TITLE).map { it.book.id })
        // 「之」同时命中书名，不命中作者
        assertEquals(listOf(2L), filterShelf(books, "之", Sort.TITLE).map { it.book.id })
    }

    @Test
    fun `查询与标签组合为 AND`() {
        // 「斗」命中 b1（无 b3），标签「玄幻」只命中 b1/b2 → 交集 b1
        assertEquals(
            listOf(1L),
            filterShelf(books, "斗", Sort.TITLE, fantasy.id, tagMap).map { it.book.id },
        )
        // 查询命中但标签不命中 → 空
        assertTrue(filterShelf(books, "完美", Sort.TITLE, fantasy.id, tagMap).isEmpty())
        // 标签命中但查询不命中 → 空
        assertTrue(filterShelf(books, "斗破", Sort.TITLE, urban.id, tagMap).isEmpty())
    }

    @Test
    fun `标签筛选命中与未命中`() {
        assertEquals(listOf(2L), filterShelf(books, "", Sort.TITLE, urban.id, tagMap).map { it.book.id })
        assertEquals(
            setOf(1L, 2L),
            filterShelf(books, "", Sort.TITLE, fantasy.id, tagMap).map { it.book.id }.toSet(),
        )
        // 不存在的标签 id → 无结果
        assertTrue(filterShelf(books, "", Sort.TITLE, 99L, tagMap).isEmpty())
    }

    @Test
    fun `空标签映射时标签筛选无结果全部筛选返回全部`() {
        assertTrue(filterShelf(books, "", Sort.TITLE, fantasy.id, emptyMap()).isEmpty())
        assertEquals(3, filterShelf(books, "", Sort.TITLE, null, emptyMap()).size)
        // 默认参数（不传 tagMap）等价于「全部」
        assertEquals(3, filterShelf(books, "", Sort.TITLE).size)
    }

    @Test
    fun `排序：最近阅读与添加时间`() {
        assertEquals(listOf(2L, 1L, 3L), filterShelf(books, "", Sort.RECENT).map { it.book.id })
        assertEquals(listOf(2L, 3L, 1L), filterShelf(books, "", Sort.ADDED).map { it.book.id })
    }

    @Test
    fun `排序：书名与作者（无作者排最后）`() {
        // 完美世界 < 斗破苍穹 < 诡秘之主（Unicode 升序）
        assertEquals(listOf(3L, 1L, 2L), filterShelf(books, "", Sort.TITLE).map { it.book.id })
        // 天蚕土豆 < 爱潜水的乌贼，无作者置末
        assertEquals(listOf(1L, 2L, 3L), filterShelf(books, "", Sort.AUTHOR).map { it.book.id })
    }

    @Test
    fun `标签与排序组合`() {
        // 玄幻命中 b1/b2；按最近阅读降序 → b2(500) 在前
        assertEquals(
            listOf(2L, 1L),
            filterShelf(books, "", Sort.RECENT, fantasy.id, tagMap).map { it.book.id },
        )
        // 同一集合按书名升序 → 斗破苍穹 在前
        assertEquals(
            listOf(1L, 2L),
            filterShelf(books, "", Sort.TITLE, fantasy.id, tagMap).map { it.book.id },
        )
    }

    @Test
    fun `无匹配书籍返回空列表`() {
        assertTrue(filterShelf(books, "不存在的书名", Sort.RECENT, null, tagMap).isEmpty())
        assertTrue(filterShelf(emptyList(), "", Sort.RECENT, null, tagMap).isEmpty())
    }

    @Test
    fun `批量打标初始勾选为标签交集`() {
        // 两本都有的标签：玄幻
        assertEquals(setOf(1L), commonTagIds(setOf(1L, 2L), tagMap))
        // 只有一本 → 该本自己的标签
        assertEquals(setOf(1L, 2L), commonTagIds(setOf(2L), tagMap))
        // 其中一本没有标签 → 交集为空
        assertTrue(commonTagIds(setOf(1L, 3L), tagMap).isEmpty())
        // 未选书籍 / 空映射
        assertTrue(commonTagIds(emptySet(), tagMap).isEmpty())
        assertTrue(commonTagIds(setOf(1L, 2L), emptyMap()).isEmpty())
    }

    @Test
    fun `卡片标签摘要最多两个加溢出标记`() {
        assertNull(tagSummary(emptyList()))
        assertEquals("玄幻", tagSummary(listOf(fantasy)))
        assertEquals("玄幻 · 都市", tagSummary(listOf(fantasy, urban)))
        assertEquals(
            "玄幻 · 都市 +1",
            tagSummary(listOf(fantasy, urban, Tag(3, "科幻"))),
        )
        assertEquals("玄幻 +2", tagSummary(listOf(fantasy, urban, Tag(3, "科幻")), maxVisible = 1))
    }
}
