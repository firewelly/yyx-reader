package com.yyx.reader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.entity.BookEntity
import com.yyx.reader.core.database.entity.BookmarkEntity
import com.yyx.reader.core.database.entity.ChapterEntity
import com.yyx.reader.core.database.entity.ReadingProgressEntity
import com.yyx.reader.core.database.entity.ScanFolderEntity
import com.yyx.reader.core.database.entity.TagEntity
import com.yyx.reader.core.datastore.ListenSettings
import com.yyx.reader.core.datastore.ReaderSettings
import com.yyx.reader.core.datastore.ReaderSettingsRepository
import com.yyx.reader.core.domain.model.BackupResult
import com.yyx.reader.data.backup.BackupFormat
import com.yyx.reader.data.backup.BackupRepositoryImpl
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 备份导出 → 清库 → 导入 roundtrip（真实 Room + org.json + 临时文件）。
 *
 * 覆盖：books / chapters / reading_progress / bookmarks / tags / book_tags / scan_folders
 * 七张表的行级恢复（含显式 id 保留与级联关系），以及 settings 快照恢复；
 * 另覆盖三条拒绝路径：非法 JSON、更高 schema、空备份。
 *
 * 设置恢复会写入真实 DataStore，因此用例前后对阅读 / 听书设置做快照与还原，
 * 避免污染设备上的用户设置。
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundtripTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "qa-backup-roundtrip.db"

    private lateinit var db: YYXDatabase
    private lateinit var settingsRepository: ReaderSettingsRepository
    private lateinit var backupRepository: BackupRepositoryImpl
    private lateinit var backupFile: File

    private var originalReader: ReaderSettings? = null
    private var originalListen: ListenSettings? = null

    private var bookId: Long = 0
    private var chapterId0: Long = 0
    private var chapterId1: Long = 0
    private var bookmarkId: Long = 0

    @Before
    fun setUp() {
        runBlocking {
            context.deleteDatabase(dbName)
            db = QaTestSupport.createDatabase(context, dbName)
            settingsRepository = ReaderSettingsRepository(context)
            backupRepository = BackupRepositoryImpl(context, db, settingsRepository)
            backupFile = File(context.cacheDir, "qa-backup-roundtrip.json").apply { delete() }

            originalReader = settingsRepository.currentReaderSettings()
            originalListen = settingsRepository.currentListenSettings()

            seedData()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching { originalReader?.let { settingsRepository.applyReaderSettings(it) } }
            runCatching { originalListen?.let { settingsRepository.applyListenSettings(it) } }
            db.close()
            context.deleteDatabase(dbName)
            backupFile.delete()
        }
    }

    private suspend fun seedData() {
        bookId = db.bookDao().insert(
            BookEntity(
                title = "备份测试书",
                author = "QA",
                format = "txt",
                fileUri = "file:///qa/backup-test.txt",
                charset = "UTF-8",
                chapterCount = 2,
                wordCount = 128,
                fileMtime = 111L,
                fileSize = 222L,
                lastReadAt = 333L,
                addedAt = 1000L,
                updatedAt = 1000L,
            ),
        )
        assertTrue("书籍插入失败", bookId > 0)

        db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = bookId,
                    idx = 0,
                    title = "第1章 起",
                    startOffset = 0,
                    endOffset = 50,
                    charCount = 50,
                ),
            ),
        )
        // insertAll 无返回值，改为按唯一索引回查主键（备份强调保留原 id）
        val chapters = db.chapterDao().getByBook(bookId)
        assertEquals(1, chapters.size)
        chapterId0 = chapters[0].id
        db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = bookId,
                    idx = 1,
                    title = "第2章 承",
                    startOffset = 50,
                    endOffset = 128,
                    charCount = 78,
                ),
            ),
        )
        assertEquals(2, db.chapterDao().getByBook(bookId).size)
        chapterId1 = db.chapterDao().getByBook(bookId)[1].id

        db.progressDao().upsert(
            ReadingProgressEntity(
                bookId = bookId,
                chapterIdx = 1,
                charOffset = 42,
                percent = 0.5f,
                source = "listen",
                updatedAt = 4444L,
            ),
        )

        bookmarkId = db.bookmarkDao().insert(
            BookmarkEntity(
                bookId = bookId,
                chapterIdx = 0,
                charOffset = 0,
                excerpt = "本书是 QA 备份测试用书",
                createdAt = 5555L,
            ),
        )
        assertTrue(bookmarkId > 0)

        val fantasy = db.tagDao().insertTag(TagEntity(name = "玄幻"))
        val urban = db.tagDao().insertTag(TagEntity(name = "都市"))
        assertEquals(2, db.tagDao().observeTags().first().size)
        db.tagDao().linkAll(
            listOf(
                com.yyx.reader.core.database.entity.BookTagEntity(bookId = bookId, tagId = fantasy),
                com.yyx.reader.core.database.entity.BookTagEntity(bookId = bookId, tagId = urban),
            ),
        )
        assertEquals(setOf("玄幻", "都市"), db.tagDao().tagsOfBook(bookId).map { it.name }.toSet())

        db.scanFolderDao().insert(
            ScanFolderEntity(
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
                displayName = "Download",
                addedAt = 6666L,
            ),
        )

        settingsRepository.applyReaderSettings(
            ReaderSettings(fontSizeSp = 24, lineSpacingDp = 12, theme = "NIGHT"),
        )
        settingsRepository.applyListenSettings(
            ListenSettings(rate = 1.5f, enginePackage = "com.reecedunn.espeak"),
        )
    }

    /** 清空七张表并复位设置，模拟「pm clear 后重新安装」的空库 */
    private suspend fun clearEverything() {
        val writable = db.openHelper.writableDatabase
        BackupFormat.CLEAR_ORDER.forEach { table ->
            if (table in QaTestSupport.tableNames(db)) writable.execSQL("DELETE FROM `$table`")
        }
        settingsRepository.applyReaderSettings(ReaderSettings())
        settingsRepository.applyListenSettings(ListenSettings())
        assertEquals(0, db.bookDao().count())
        assertEquals("DAY", settingsRepository.currentReaderSettings().theme)
    }

    @Test
    fun exportWritesCompleteJson() = runBlocking {
        val result = backupRepository.export(Uri.fromFile(backupFile))

        assertTrue("导出应成功，实际：$result", result is BackupResult.Success)
        val summary = (result as BackupResult.Success).summary
        assertTrue("备份文件应非空", backupFile.length() > 0)
        assertEquals(backupFile.length(), summary.byteCount)
        assertTrue("导出应包含设置", summary.settingsIncluded)
        assertEquals(1, summary.tableCounts["books"])
        assertEquals(2, summary.tableCounts["chapters"])
        assertEquals(1, summary.tableCounts["reading_progress"])
        assertEquals(1, summary.tableCounts["bookmarks"])
        assertEquals(2, summary.tableCounts["tags"])
        assertEquals(2, summary.tableCounts["book_tags"])
        assertEquals(1, summary.tableCounts["scan_folders"])
        assertTrue("v2 库不应跳过任何表", summary.skippedTables.isEmpty())

        val root = JSONObject(backupFile.readText(Charsets.UTF_8))
        assertEquals(1, root.getInt("schema"))
        assertEquals("com.yyx.reader", root.getString("app"))
        assertTrue(root.getLong("exportedAt") > 0)
        val settings = root.getJSONObject("settings")
        assertEquals(24, settings.getInt("fontSizeSp"))
        assertEquals(12, settings.getInt("lineSpacingDp"))
        assertEquals("NIGHT", settings.getString("theme"))
        assertEquals(1.5, settings.getDouble("ttsRate"), 0.0001)
        assertEquals("com.reecedunn.espeak", settings.getString("ttsEngine"))
        assertEquals(1, root.getJSONArray("books").length())
        assertEquals(2, root.getJSONArray("chapters").length())
        assertEquals(1, root.getJSONArray("reading_progress").length())
        assertEquals(1, root.getJSONArray("bookmarks").length())
        assertEquals(2, root.getJSONArray("tags").length())
        assertEquals(2, root.getJSONArray("book_tags").length())
        assertEquals(1, root.getJSONArray("scan_folders").length())
    }

    @Test
    fun importRestoresDataAndSettings() = runBlocking {
        assertTrue(backupRepository.export(Uri.fromFile(backupFile)) is BackupResult.Success)
        clearEverything()

        val result = backupRepository.import(Uri.fromFile(backupFile))
        assertTrue("导入应成功，实际：$result", result is BackupResult.Success)
        val summary = (result as BackupResult.Success).summary
        assertTrue("导入应包含并成功应用设置", summary.settingsIncluded)
        assertEquals(10, summary.totalRows)
        // V1.3：导入后新增「恢复后书源可访问性抽检」（AGENTS 待办 8）。本用例 seed 的书籍
        // fileUri=file:///qa/backup-test.txt 并不存在，因此必然出现「书源不可访问」中文告警；
        // 这里只要求不出现数据类告警（重复主键 / 引用完整性 / 保留本机表）。
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val accessibilityWarnings = setOf(
            ctx.getString(com.yyx.reader.R.string.backup_warn_sources_unreadable, 1),
            ctx.getString(com.yyx.reader.R.string.backup_warn_folders_unreadable, 1),
        )
        // 多语言无关：除可访问性告警外不应出现任何数据类告警（重复主键/引用完整性/保留本机表）
        assertTrue(
            "不应出现数据类告警：${summary.warnings}",
            summary.warnings.none { it !in accessibilityWarnings },
        )
        // 新增告警断言①：测试书源文件不存在 → 必须给出书源不可访问告警（多语言：按资源断言）
        val sourceWarn = ctx.getString(com.yyx.reader.R.string.backup_warn_sources_unreadable, 1)
        val folderWarn = ctx.getString(com.yyx.reader.R.string.backup_warn_folders_unreadable, 1)
        assertTrue(
            "测试书的 file:// 源文件不存在，应给出书源告警：${summary.warnings}",
            summary.warnings.any { it == sourceWarn || it == folderWarn },
        )

        // ---- 表数据按原 id 恢复 ----
        assertEquals(1, db.bookDao().count())
        val book = db.bookDao().getById(bookId)
        assertNotNull("书籍 id 应保留", book)
        assertEquals("备份测试书", book!!.title)
        assertEquals("QA", book.author)
        assertEquals("UTF-8", book.charset)
        assertEquals(2, book.chapterCount)

        val chapters = db.chapterDao().getByBook(bookId)
        assertEquals(listOf(0, 1), chapters.map { it.idx })
        assertEquals(listOf("第1章 起", "第2章 承"), chapters.map { it.title })
        assertEquals(chapterId0, chapters[0].id)
        assertEquals(chapterId1, chapters[1].id)

        val progress = db.progressDao().get(bookId)
        assertNotNull(progress)
        assertEquals(1, progress!!.chapterIdx)
        assertEquals(42, progress.charOffset)
        assertEquals(0.5f, progress.percent, 0.0001f)
        assertEquals("listen", progress.source)

        val bookmarks = db.bookmarkDao().observeByBook(bookId).first()
        assertEquals(1, bookmarks.size)
        assertEquals(bookmarkId, bookmarks[0].id)
        assertEquals("本书是 QA 备份测试用书", bookmarks[0].excerpt)

        assertEquals(setOf("玄幻", "都市"), db.tagDao().observeTags().first().map { it.name }.toSet())
        assertEquals(setOf("玄幻", "都市"), db.tagDao().tagsOfBook(bookId).map { it.name }.toSet())

        val folders = db.scanFolderDao().getAll()
        assertEquals(1, folders.size)
        assertEquals("Download", folders[0].displayName)

        // ---- 设置恢复 ----
        val reader = settingsRepository.currentReaderSettings()
        assertEquals(24, reader.fontSizeSp)
        assertEquals(12, reader.lineSpacingDp)
        assertEquals("NIGHT", reader.theme)
        val listen = settingsRepository.currentListenSettings()
        assertEquals(1.5f, listen.rate, 0.0001f)
        assertEquals("com.reecedunn.espeak", listen.enginePackage)
    }

    @Test
    fun repeatedImportKeepsNoDuplicates() = runBlocking {
        assertTrue(backupRepository.export(Uri.fromFile(backupFile)) is BackupResult.Success)
        assertTrue(backupRepository.import(Uri.fromFile(backupFile)) is BackupResult.Success)
        val second = backupRepository.import(Uri.fromFile(backupFile))
        assertTrue("二次导入应成功，实际：$second", second is BackupResult.Success)

        assertEquals(1, db.bookDao().count())
        assertEquals(2, db.chapterDao().count(bookId))
        assertEquals(2, db.tagDao().observeTags().first().size)
        assertEquals(2, QaTestSupport.rowCount(db, "book_tags"))
    }

    @Test
    fun invalidJsonFailsWithoutDataLoss() = runBlocking {
        backupFile.writeText("这不是 JSON")
        val result = backupRepository.import(Uri.fromFile(backupFile))
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        assertTrue("应返回失败", result is BackupResult.Failure)
        assertTrue((result as BackupResult.Failure).message.contains(ctx.getString(com.yyx.reader.R.string.backup_error_not_json)))
        assertEquals("失败不应清空数据", 1, db.bookDao().count())
        assertEquals(2, db.tagDao().observeTags().first().size)
    }

    @Test
    fun higherSchemaRejected() = runBlocking {
        backupFile.writeText(
            """{"schema":2,"app":"com.yyx.reader","exportedAt":1,"books":[]}""",
        )
        val result = backupRepository.import(Uri.fromFile(backupFile))

        assertTrue(result is BackupResult.Failure)
        val message = (result as BackupResult.Failure).message
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("提示应包含更高版本：$message", message.contains(ctx.getString(com.yyx.reader.R.string.backup_error_schema_too_new, 2)))
        assertEquals(1, db.bookDao().count())
    }

    @Test
    fun emptyBackupRejected() = runBlocking {
        backupFile.writeText(
            """{"schema":1,"app":"com.yyx.reader","exportedAt":1,"books":[],"chapters":[]}""",
        )
        val result = backupRepository.import(Uri.fromFile(backupFile))

        assertTrue(result is BackupResult.Failure)
        assertTrue(
            (result as BackupResult.Failure).message.contains(
                ApplicationProvider.getApplicationContext<Context>().getString(com.yyx.reader.R.string.backup_error_no_data),
            ),
        )
        assertEquals(1, db.bookDao().count())
    }


    // ---------- V1.4：流式化后解除 16MB 旧上限 ----------

    @Test
    fun largeBackupRoundtripBeyondLegacy16MbLimit() = runBlocking {
        // 追加 350 章、每章约 2 万字标题（UTF-8 约 60KB），使备份 JSON > 16MB 且 < 64MB：
        // V1.3 的整串读写实现会直接拒绝这种备份，V1.4 流式实现必须能导出 + 导入。
        val filler = "填充".repeat(10_000)
        db.chapterDao().insertAll(
            (0 until 350).map { i ->
                ChapterEntity(
                    bookId = bookId,
                    idx = 100 + i,
                    title = "第${100 + i}章 $filler",
                    startOffset = -1,
                    endOffset = -1,
                    charCount = filler.length,
                )
            },
        )
        val expectedChapters = db.chapterDao().count(bookId)
        assertTrue("应已插入大量章节", expectedChapters >= 350)

        val export = backupRepository.export(Uri.fromFile(backupFile))
        assertTrue("大备份导出应成功，实际：$export", export is BackupResult.Success)
        val size = backupFile.length()
        assertTrue("备份应超过 V1.3 的 16MB 旧上限，实际 $size", size > 16L * 1024 * 1024)
        assertTrue("备份应不超过 64MB 新上限，实际 $size", size <= BackupFormat.MAX_FILE_BYTES)
        assertEquals(size, (export as BackupResult.Success).summary.byteCount)

        // 清库后导入，行数与抽样内容必须一致
        clearEverything()
        val restore = backupRepository.import(Uri.fromFile(backupFile))
        assertTrue("大备份导入应成功，实际：$restore", restore is BackupResult.Success)
        val summary = (restore as BackupResult.Success).summary
        assertEquals(expectedChapters, db.chapterDao().count(bookId))
        assertEquals(expectedChapters, summary.tableCounts["chapters"])
        assertEquals(1, summary.tableCounts["books"])
        assertTrue("标题应完整恢复", db.chapterDao().getByBook(bookId)[0].title.length > 0)
        assertEquals("不应出现数据类告警：${summary.warnings}", 0,
            summary.warnings.count { it.contains("重复主键") || it.contains("已保留本机") })
    }

    // ---------- V1.4：缺核心表键拒绝（Reviewer P2-2） ----------

    @Test
    fun missingCoreTableRejectedBeforeWipingData() = runBlocking {
        // 先导出合法备份，再手工删掉 chapters 键（模拟损坏 / 截断备份）
        val export = backupRepository.export(Uri.fromFile(backupFile))
        assertTrue(export is BackupResult.Success)
        val root = JSONObject(backupFile.readText(Charsets.UTF_8))
        root.remove("chapters")
        backupFile.writeText(root.toString())

        val booksBefore = db.bookDao().count()
        val chaptersBefore = db.chapterDao().count(bookId)
        val result = backupRepository.import(Uri.fromFile(backupFile))

        assertTrue("缺 chapters 键应被拒绝：$result", result is BackupResult.Failure)
        val message = (result as BackupResult.Failure).message
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("提示应点名核心表：$message", message.contains(ctx.getString(com.yyx.reader.R.string.backup_error_missing_core, "chapters")))
        // 关键：拒绝发生在清空之前，本机数据必须原样保留
        assertEquals(booksBefore, db.bookDao().count())
        assertEquals(chaptersBefore, db.chapterDao().count(bookId))
    }
}
