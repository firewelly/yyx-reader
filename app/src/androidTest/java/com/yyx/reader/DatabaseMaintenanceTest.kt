package com.yyx.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yyx.reader.core.database.CleanupReport
import com.yyx.reader.core.database.OrphanCleaner
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.entity.BookEntity
import com.yyx.reader.core.database.entity.BookTagEntity
import com.yyx.reader.core.database.entity.BookmarkEntity
import com.yyx.reader.core.database.entity.ChapterEntity
import com.yyx.reader.core.database.entity.ReadingProgressEntity
import com.yyx.reader.core.database.entity.TagEntity
import com.yyx.reader.core.ebook.epub.EpubChapterLoader
import com.yyx.reader.data.repository.BookRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1.4 数据库维护与删书回归（真实 Room + 独立测试库，不触碰生产 yyx.db）：
 * 1. [OrphanCleaner] 启动时维护：chapters / reading_progress / bookmarks 悬空 book_id 清理，
 *    book_tags 双条件（book_id 或 tag_id 失效）清理，有效行不受影响、幂等；
 * 2. Reviewer P3-7：`BookRepositoryImpl.deleteBook` 的 repository 级删除回归（子表全清 + 标签保留）。
 *
 * 用 [QaTestSupport.createDatabase]（与生产 YYXDatabase.build() 一致的 PRAGMA foreign_keys=ON），
 * 才能真实验证 book_tags 级联；孤儿 book_tags 用「临时关闭外键」的方式插入（模拟历史脏数据）。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMaintenanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "qa-db-maintenance.db"

    private lateinit var db: YYXDatabase
    private lateinit var cleaner: OrphanCleaner

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = QaTestSupport.createDatabase(context, dbName)
        cleaner = OrphanCleaner(
            database = db,
            chapterDao = db.chapterDao(),
            progressDao = db.progressDao(),
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    // ─────────────────────────── OrphanCleaner ───────────────────────────

    @Test
    fun cleanupRemovesOrphansAndKeepsValidRows() = runBlocking {
        val bookId = seedBook("有效书籍", "valid")
        val tagId = db.tagDao().insertTag(TagEntity(name = "玄幻"))

        // 有效行：书籍 1 本 + 章节/进度/书签/标签关联各 1 条
        seedChapter(bookId, 0, "第一章 有效")
        seedProgress(bookId)
        seedBookmark(bookId)
        db.tagDao().linkAll(listOf(BookTagEntity(bookId = bookId, tagId = tagId)))

        // 孤儿行：book_id 悬空（chapters 无外键允许直接插）
        seedChapter(999L, 0, "孤儿章节一")
        seedChapter(998L, 0, "孤儿章节二")
        seedProgress(999L)
        seedBookmark(999L)
        // 孤儿标签关联：一端失效（需临时关闭外键插入，模拟历史脏数据）
        insertBookTagIgnoringForeignKeys(bookId = 999L, tagId = tagId) // 书不存在
        insertBookTagIgnoringForeignKeys(bookId = bookId, tagId = 888L) // 标签不存在

        val report = cleaner.cleanup()

        // 各类删除条数
        assertEquals(CleanupReport(chapters = 2, progress = 1, bookmarks = 1, bookTags = 2), report)
        assertEquals(6, report.total)

        // 有效行全部保留
        assertEquals(1L, rowCount("books"))
        assertEquals(1L, rowCount("chapters"))
        assertEquals(1L, rowCount("reading_progress"))
        assertEquals(1L, rowCount("bookmarks"))
        assertEquals(1L, rowCount("book_tags"))
        assertEquals(1L, rowCount("tags"))
        assertEquals("第一章 有效", db.chapterDao().getByBook(bookId).single().title)
        assertEquals(bookId, db.progressDao().get(bookId)!!.bookId)
        assertEquals(1, db.bookmarkDao().observeByBook(bookId).first().size)
        assertEquals(listOf("玄幻"), db.tagDao().tagsOfBook(bookId).map { it.name })
    }

    @Test
    fun cleanupIsIdempotent() = runBlocking {
        val bookId = seedBook("幂等书籍", "idem")
        seedChapter(bookId, 0, "第一章")
        seedChapter(777L, 0, "孤儿章节")

        val first = cleaner.cleanup()
        assertEquals(1, first.chapters)
        assertEquals(1, first.total)

        val second = cleaner.cleanup()
        assertEquals(CleanupReport(), second)
        assertEquals(0, second.total)
        assertEquals(1L, rowCount("chapters"))
        assertNotNull(db.bookDao().getById(bookId))
    }

    /** books 全空时 `NOT IN (空集)` 恒真：所有子表行都应判为孤儿（不会因空表漏删） */
    @Test
    fun cleanupWhenNoBooksRemovesAllChildRows() = runBlocking {
        val tagId = db.tagDao().insertTag(TagEntity(name = "科幻"))
        seedChapter(42L, 0, "无主章节")
        seedProgress(42L)
        seedBookmark(42L)
        insertBookTagIgnoringForeignKeys(bookId = 42L, tagId = tagId)

        val report = cleaner.cleanup()

        assertEquals(CleanupReport(chapters = 1, progress = 1, bookmarks = 1, bookTags = 1), report)
        assertEquals(0L, rowCount("books"))
        assertEquals(0L, rowCount("chapters"))
        assertEquals(0L, rowCount("reading_progress"))
        assertEquals(0L, rowCount("bookmarks"))
        assertEquals(0L, rowCount("book_tags"))
        assertEquals("标签本体不属于孤儿，应保留", 1L, rowCount("tags"))
    }

    // ─────────────── Reviewer P3-7：repository 级删书回归 ───────────────

    @Test
    fun deleteBookClearsChildTablesViaRepository() = runBlocking {
        val bookId = seedBook("待删书籍", "del")
        val tagId = db.tagDao().insertTag(TagEntity(name = "都市"))
        seedChapter(bookId, 0, "第一章 起")
        seedChapter(bookId, 1, "第二章 承")
        seedProgress(bookId)
        seedBookmark(bookId)
        db.tagDao().linkAll(listOf(BookTagEntity(bookId = bookId, tagId = tagId)))

        // 直接构造 BookRepositoryImpl（与生产相同的 7 个依赖）
        val repository = BookRepositoryImpl(
            context = context,
            database = db,
            bookDao = db.bookDao(),
            chapterDao = db.chapterDao(),
            progressDao = db.progressDao(),
            bookmarkDao = db.bookmarkDao(),
            epubLoader = EpubChapterLoader(context),
        )

        assertTrue("删书应返回 true", repository.deleteBook(bookId, deleteSourceFile = false))

        // 书籍与全部子表行（含 book_tags 级联）都应为空
        assertNull(db.bookDao().getById(bookId))
        assertEquals(0L, rowCount("books"))
        assertEquals(0L, rowCount("chapters"))
        assertEquals(0L, rowCount("reading_progress"))
        assertEquals(0L, rowCount("bookmarks"))
        assertEquals(0L, rowCount("book_tags"))
        assertEquals("标签本体不应被删", 1L, rowCount("tags"))

        // 再删不存在的书：返回 false，不崩溃
        assertFalse(repository.deleteBook(bookId, deleteSourceFile = false))
    }

    // ─────────────────────────── 种子 / 查询辅助 ───────────────────────────

    private suspend fun seedBook(title: String, uriSuffix: String): Long {
        val now = System.currentTimeMillis()
        return db.bookDao().insert(
            BookEntity(
                title = title,
                format = "txt",
                fileUri = "file:///qa/db-maintenance-$uriSuffix.txt",
                chapterCount = 1,
                wordCount = 10,
                addedAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun seedChapter(bookId: Long, idx: Int, title: String) {
        db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = bookId,
                    idx = idx,
                    title = title,
                    startOffset = 0,
                    endOffset = 10,
                    charCount = 10,
                ),
            ),
        )
    }

    private suspend fun seedProgress(bookId: Long) {
        db.progressDao().upsert(
            ReadingProgressEntity(
                bookId = bookId,
                chapterIdx = 0,
                charOffset = 5,
                percent = 0.1f,
                source = "read",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun seedBookmark(bookId: Long) {
        db.bookmarkDao().insert(
            BookmarkEntity(
                bookId = bookId,
                chapterIdx = 0,
                charOffset = 1,
                excerpt = "摘录",
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 插入「任一端失效」的 book_tags 行：生产库外键开着，需临时关闭才能写入历史脏数据 */
    private fun insertBookTagIgnoringForeignKeys(bookId: Long, tagId: Long) {
        val writable = db.openHelper.writableDatabase
        writable.execSQL("PRAGMA foreign_keys = OFF")
        try {
            writable.execSQL(
                "INSERT OR IGNORE INTO book_tags (book_id, tag_id) VALUES (?, ?)",
                arrayOf<Any>(bookId, tagId),
            )
        } finally {
            writable.execSQL("PRAGMA foreign_keys = ON")
        }
    }

    private fun rowCount(table: String): Long =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
