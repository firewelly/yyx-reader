package com.yyx.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.entity.BookEntity
import com.yyx.reader.core.database.entity.BookTagEntity
import com.yyx.reader.core.database.entity.TagEntity
import com.yyx.reader.core.domain.model.Tag
import com.yyx.reader.data.repository.TagRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F-13 标签数据层仪器测试（真实 Room + 外键约束）：TagDao CRUD / 重名与空名 / 长度上限、
 * book_tags 关联读写、标签删除级联、书籍删除级联、重复打标幂等。
 *
 * 使用独立测试库（[QaTestSupport.createDatabase]），不触碰设备上的真实书库。
 */
@RunWith(AndroidJUnit4::class)
class TagDaoTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "qa-tag-dao.db"

    private lateinit var db: YYXDatabase
    private lateinit var repository: TagRepositoryImpl

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = QaTestSupport.createDatabase(context, dbName)
        repository = TagRepositoryImpl(db.tagDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private suspend fun insertBook(title: String, uriSuffix: String): Long {
        val now = System.currentTimeMillis()
        return db.bookDao().insert(
            BookEntity(
                title = title,
                format = "txt",
                fileUri = "file:///qa/tag-dao-$uriSuffix.txt",
                chapterCount = 1,
                wordCount = 10,
                addedAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun createValidatesNameAndLimits() = runBlocking {
        assertNull("空名应拒绝", repository.create(""))
        assertNull("全空白应拒绝", repository.create("   "))

        // 首尾空白会被 trim
        val id = repository.create("  玄幻  ")
        assertNotNull("正常名称应创建成功", id)
        assertEquals("玄幻", db.tagDao().observeTags().first().single().name)

        // 唯一索引冲突（IGNORE 返回 -1 → null）
        assertNull("重名应拒绝", repository.create("玄幻"))

        // 超过 20 字截断
        val longId = repository.create("标".repeat(30))
        assertNotNull(longId)
        val created = db.tagDao().observeTags().first().first { it.id == longId }
        assertEquals(20, created.name.length)
    }

    @Test
    fun linkQueryAndTagMap() = runBlocking {
        val book1 = insertBook("标签书一", "1")
        val book2 = insertBook("标签书二", "2")
        val fantasy = repository.create("玄幻")!!
        val urban = repository.create("都市")!!

        repository.addToBooks(listOf(book1), fantasy)
        repository.addToBooks(listOf(book1, book2), urban)

        assertEquals(listOf("玄幻", "都市"), repository.tagsOf(book1).map { it.name })
        assertEquals(listOf("都市"), repository.tagsOf(book2).map { it.name })
        assertTrue("未打标的书应返回空", repository.tagsOf(999L).isEmpty())

        val tagMap = repository.observeTagMap().first()
        assertEquals(listOf("玄幻", "都市"), tagMap.getValue(book1).map(Tag::name))
        assertEquals(listOf("都市"), tagMap.getValue(book2).map(Tag::name))

        // 取消勾选移除（unlink）
        repository.removeFromBooks(listOf(book1), fantasy)
        assertEquals(listOf("都市"), repository.tagsOf(book1).map { it.name })

        // 重复打标幂等（复合主键 + IGNORE）
        repository.addToBooks(listOf(book2), urban)
        assertEquals(1, db.tagDao().tagsOfBook(book2).size)
    }

    @Test
    fun deleteTagCascadesBookTags() = runBlocking {
        val bookId = insertBook("待删标签书", "del")
        val tagId = repository.create("玄幻")!!
        repository.addToBooks(listOf(bookId), tagId)
        assertEquals(1, QaTestSupport.rowCount(db, "book_tags"))

        repository.delete(tagId)

        assertTrue("标签应被删除", db.tagDao().observeTags().first().isEmpty())
        assertEquals("book_tags 应级联清空", 0, QaTestSupport.rowCount(db, "book_tags"))
        assertNotNull("书籍本体不受影响", db.bookDao().getById(bookId))
        assertTrue(repository.tagsOf(bookId).isEmpty())
    }

    @Test
    fun deleteBookCascadesBookTags() = runBlocking {
        val bookId = insertBook("待删书籍", "delbook")
        val tagId = repository.create("都市")!!
        repository.addToBooks(listOf(bookId), tagId)
        assertEquals(1, QaTestSupport.rowCount(db, "book_tags"))

        db.bookDao().delete(bookId)

        assertNull("书籍应被删除", db.bookDao().getById(bookId))
        assertEquals("book_tags 应级联清空", 0, QaTestSupport.rowCount(db, "book_tags"))
        assertEquals(listOf("都市"), db.tagDao().observeTags().first().map { it.name })
        assertEquals("标签应保留", 1, QaTestSupport.rowCount(db, "tags"))
    }

    @Test
    fun linkAllIgnoresDuplicateRows() = runBlocking {
        val bookId = insertBook("幂等书", "idem")
        val tagId = db.tagDao().insertTag(TagEntity(name = "科幻"))

        db.tagDao().linkAll(
            listOf(
                BookTagEntity(bookId = bookId, tagId = tagId),
                BookTagEntity(bookId = bookId, tagId = tagId),
            ),
        )
        assertEquals(1, QaTestSupport.rowCount(db, "book_tags"))
    }
}
