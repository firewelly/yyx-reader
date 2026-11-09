package com.yyx.reader.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.entity.BookTagEntity
import com.yyx.reader.core.database.entity.TagEntity
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
 * V1.3 迁移回归：**真实 v1 库 → v2（Room 打开触发迁移）** 的仪器测试。
 *
 * 与 V1.2 的「一次性 SQL 逐字核对」不同，这里把迁移固化为可重复执行的用例：
 * 1. 用原始 SQLite 在测试私有目录构造 v1 库（DDL / identity_hash 取自 V1.2 真实库 /tmp/yyx-lead-v1.db）；
 * 2. 用正式构建路径（Room.databaseBuilder + [YYXDatabase.MIGRATION_1_2]）打开，触发 Room 自身的
 *    onUpgrade → Migration.migrate → **onValidateSchema**（schema 不一致会抛
 *    "Migration didn't properly handle"）→ updateIdentity；
 * 3. 断言迁移成功、旧数据保留、v2 新表可 CRUD/级联、幂等重开。
 *
 * 不引入 room-testing：不使用 MigrationTestHelper，因此也不需要把 schema 放进 androidTest assets；
 * app/schemas/.../2.json 作为仓库内的 schema 基准供后续 v2→v3 扩展（见任务文档）。
 *
 * 后续 v2→v3 扩展方式（维护提示）：
 * - 一旦 @Database version 升到 3，本文件的 [openMigrated] 需追加 `addMigrations(YYXDatabase.MIGRATION_2_3)`
 *   并把 user_version 断言改为 3（顺带验证 1→3 链式迁移），否则 Room 会因缺 2→3 迁移直接失败；
 * - 再新增 `MigrationV2ToV3Test`，以本次导出的 `app/schemas/.../2.json` 为基准构造 v2 库（DDL 即 2.json
 *   的 createSql + identityHash 字段），沿用本文件「自建旧库 → Room 打开 → 断言」的模式；
 * - 本用例的「legacy 数据保留」断言应保留（它们是迁移防退化的核心断言）。
 *
 * 使用独立库名 [DB_NAME]，不触碰设备上的生产库 yyx.db。
 */
@RunWith(AndroidJUnit4::class)
class MigrationV1ToV2Test {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private var db: YYXDatabase? = null

    @Before
    fun setUp() {
        closeDatabase()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        closeDatabase()
        context.deleteDatabase(DB_NAME)
    }

    // ─────────────────────────── 用例 ───────────────────────────

    @Test
    fun migrateFromV1PreservesSchemaAndLegacyData() {
        createV1Database()
        openMigrated()

        // 版本与新增表
        assertEquals("迁移后 user_version 应为 2", 2L, rawLong("PRAGMA user_version"))
        assertTrue("应存在 tags 表", tableExists("tags"))
        assertTrue("应存在 book_tags 表", tableExists("book_tags"))
        assertEquals("新表初始为空", 0L, rawLong("SELECT COUNT(*) FROM tags"))
        assertEquals("新表初始为空", 0L, rawLong("SELECT COUNT(*) FROM book_tags"))

        // 旧数据行数全部保留
        assertEquals(2L, rawLong("SELECT COUNT(*) FROM books"))
        assertEquals(3L, rawLong("SELECT COUNT(*) FROM chapters"))
        assertEquals(2L, rawLong("SELECT COUNT(*) FROM reading_progress"))
        assertEquals(1L, rawLong("SELECT COUNT(*) FROM bookmarks"))
        assertEquals(1L, rawLong("SELECT COUNT(*) FROM scan_folders"))

        runBlocking {
            // 书籍（含 NULL 字段与原 autoincrement id）
            val book1 = db!!.bookDao().getById(1L)
            assertNotNull("v1 书籍 1 应保留", book1)
            assertEquals("斗破苍穹", book1!!.title)
            assertEquals("天蚕土豆", book1.author)
            assertEquals("txt", book1.format)
            assertEquals("content://legacy/book-1", book1.fileUri)
            assertEquals(2, book1.chapterCount)
            assertEquals(1500L, book1.lastReadAt)

            val book2 = db!!.bookDao().getById(2L)
            assertNotNull("v1 书籍 2 应保留", book2)
            assertEquals("诡秘之主", book2!!.title)
            assertNull("NULL 作者应保持 NULL", book2.author)
            assertEquals("epub", book2.format)

            // 章节（含唯一索引 book_id + idx）
            val chapters1 = db!!.chapterDao().getByBook(1L)
            assertEquals(2, chapters1.size)
            assertEquals("第一章 陨落的天才", chapters1[0].title)
            assertEquals("第二章 斗气大陆", chapters1[1].title)
            assertEquals(150, chapters1[1].charCount)
            val chapters2 = db!!.chapterDao().getByBook(2L)
            assertEquals(1, chapters2.size)
            assertEquals("text/0001.xhtml", chapters2[0].epubHref)

            // 统一进度（阅读/听书两来源）
            val progress1 = db!!.progressDao().get(1L)
            assertNotNull(progress1)
            assertEquals(1, progress1!!.chapterIdx)
            assertEquals(120, progress1.charOffset)
            assertEquals(0.4f, progress1.percent, 1e-6f)
            assertEquals("read", progress1.source)
            val progress2 = db!!.progressDao().get(2L)
            assertEquals("listen", progress2!!.source)

            // 书签
            val bookmarks = db!!.bookmarkDao().observeByBook(1L).first()
            assertEquals(1, bookmarks.size)
            assertEquals("三十年河东", bookmarks[0].excerpt)
            assertEquals(12, bookmarks[0].charOffset)

            // 扫描文件夹
            val folders = db!!.scanFolderDao().observeAll().first()
            assertEquals(1, folders.size)
            assertEquals("novels", folders[0].displayName)
        }

        // v2 新表结构：唯一索引 / 外键 / 索引（外键必须 CASCADE，否则删书删标签不清关联）
        assertTrue("tags.name 唯一索引应存在", uniqueIndexNames("tags").contains("index_tags_name"))
        assertTrue(
            "book_tags.book_id 索引应存在",
            indexNames("book_tags").contains("index_book_tags_book_id"),
        )
        assertTrue(
            "book_tags.tag_id 索引应存在",
            indexNames("book_tags").contains("index_book_tags_tag_id"),
        )
        assertEquals(
            "book_tags 应对 books/tags 各有一个级联外键",
            setOf("books" to "CASCADE", "tags" to "CASCADE"),
            foreignKeyDeleteActions("book_tags"),
        )

        assertEquals("PRAGMA integrity_check 应通过", "ok", integrityCheck())
    }

    @Test
    fun migratedDatabaseSupportsV2CrudAndCascade() = runBlocking {
        createV1Database()
        openMigrated()

        // 1) 新增标签 + 给旧书打标
        val tagId = db!!.tagDao().insertTag(TagEntity(name = "玄幻"))
        assertTrue("迁移后应能插入标签", tagId > 0)
        db!!.tagDao().linkAll(
            listOf(
                BookTagEntity(bookId = 1L, tagId = tagId),
                BookTagEntity(bookId = 2L, tagId = tagId),
            ),
        )
        assertEquals(2L, rawLong("SELECT COUNT(*) FROM book_tags"))
        assertEquals(listOf("玄幻"), db!!.tagDao().tagsOfBook(1L).map { it.name })

        // 2) 删书：book_tags 级联清理（FK ON 由测试 builder 的 onOpen 回调开启，同生产 build()）
        db!!.bookDao().delete(1L)
        assertNull("书籍应被删除", db!!.bookDao().getById(1L))
        assertEquals(1L, rawLong("SELECT COUNT(*) FROM books"))
        assertEquals("删书应级联清 book_tags", 1L, rawLong("SELECT COUNT(*) FROM book_tags"))

        // 3) 删标签：book_tags 级联清理，标签本体消失
        db!!.tagDao().deleteTag(tagId)
        assertEquals(0L, rawLong("SELECT COUNT(*) FROM tags"))
        assertEquals("删标签应级联清 book_tags", 0L, rawLong("SELECT COUNT(*) FROM book_tags"))

        // 4) 再删一本不崩（chapters 无 DB 外键＝既有债，只断言 books 删除成功、章节不被误删）
        db!!.bookDao().delete(2L)
        assertEquals(0L, rawLong("SELECT COUNT(*) FROM books"))
        assertEquals("chapters 无外键，删书后仍保留（既有债，非本次迁移引入）", 3L, rawLong("SELECT COUNT(*) FROM chapters"))
        assertEquals("ok", integrityCheck())
    }

    @Test
    fun reopenAfterMigrationIsIdempotent() = runBlocking {
        createV1Database()
        openMigrated()
        // 迁移后的 v2 数据，用于验证重开不会丢/重复
        val tagId = db!!.tagDao().insertTag(TagEntity(name = "都市"))
        db!!.tagDao().linkAll(listOf(BookTagEntity(bookId = 1L, tagId = tagId)))
        closeDatabase()

        repeat(2) { round ->
            openMigrated() // 已是 v2：Room 只做 schema 校验，不再执行迁移
            assertEquals("第 ${round + 1} 次重开版本仍为 2", 2L, rawLong("PRAGMA user_version"))
            assertEquals("旧书应仍在", 2L, rawLong("SELECT COUNT(*) FROM books"))
            assertEquals("第 ${round + 1} 次重开后标签仍在", 1L, rawLong("SELECT COUNT(*) FROM tags"))
            assertEquals("第 ${round + 1} 次重开后关联仍在", 1L, rawLong("SELECT COUNT(*) FROM book_tags"))
            assertEquals("ok", integrityCheck())
            closeDatabase()
        }
    }

    /**
     * 异常场景（任务可选）：v1 库缺 room_master_table。
     *
     * 实测 Room 2.6.1 的 RoomOpenHelper.updateIdentity() 会先 `createMasterTableIfNotExists`，
     * 因此缺表**不会**抛异常，迁移照常成功——此用例把该真实行为固化下来（若未来 Room 升级改为抛错，
     * 本用例会失败并提醒重新评估异常处理策略）。
     */
    @Test
    fun v1DatabaseWithoutRoomMasterTableStillMigrates() {
        createV1Database(withRoomMasterTable = false)
        openMigrated()

        assertEquals(2L, rawLong("PRAGMA user_version"))
        assertTrue(tableExists("room_master_table"))
        assertEquals(2L, rawLong("SELECT COUNT(*) FROM books"))
        assertTrue(tableExists("tags"))
        assertTrue(tableExists("book_tags"))
        assertEquals("ok", integrityCheck())
    }

    // ─────────────────────────── 构造 v1 库 ───────────────────────────

    /** 用原始 SQLite 造真实 v1 库：v1 DDL + user_version=1 + v1 identity_hash + 种子数据 */
    private fun createV1Database(withRoomMasterTable: Boolean = true) {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        assertTrue("清理旧测试库失败", !file.exists() || file.delete())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            V1_DDL.forEach(v1::execSQL)
            if (withRoomMasterTable) {
                v1.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                    arrayOf(V1_IDENTITY_HASH),
                )
            }
            v1.execSQL("INSERT INTO android_metadata (locale) VALUES ('en_US')")
            SEED_SQL.forEach(v1::execSQL)
            v1.version = 1 // PRAGMA user_version = 1
        }
    }

    // ─────────────────────────── 打开当前版本（含迁移） ───────────────────────────

    /** 与 YYXDatabase.build() 等价的打开路径：显式 addMigrations(MIGRATION_1_2) + 外键 ON 回调 */
    private fun openMigrated() {
        closeDatabase()
        val database = Room.databaseBuilder(context, YYXDatabase::class.java, DB_NAME)
            .addMigrations(YYXDatabase.MIGRATION_1_2)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // 生产 build() 同样在 onOpen 开启外键（级联依赖它）
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                },
            )
            .build()
        db = database
        // Room 懒打开：显式取一次 writableDatabase，确保 onUpgrade→Migration→校验 已执行完
        database.openHelper.writableDatabase
    }

    private fun closeDatabase() {
        db?.close()
        db = null
    }

    // ─────────────────────────── 查询断言辅助 ───────────────────────────

    private fun rawLong(sql: String): Long =
        db!!.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue("查询无结果: $sql", cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun tableExists(table: String): Boolean =
        rawLong("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'") == 1L

    private fun integrityCheck(): String =
        db!!.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    /** PRAGMA index_list：index 名 → 是否唯一 */
    private fun indexUniqueMap(table: String): Map<String, Boolean> =
        db!!.openHelper.readableDatabase.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildMap {
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val uniqueIdx = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) put(cursor.getString(nameIdx), cursor.getInt(uniqueIdx) == 1)
            }
        }

    private fun indexNames(table: String): Set<String> = indexUniqueMap(table).keys

    private fun uniqueIndexNames(table: String): Set<String> =
        indexUniqueMap(table).filterValues { it }.keys

    /** PRAGMA foreign_key_list：被引用表名 → ON DELETE 动作 */
    private fun foreignKeyDeleteActions(table: String): Set<Pair<String, String>> =
        db!!.openHelper.readableDatabase.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildSet {
                val tableIdx = cursor.getColumnIndexOrThrow("table")
                val onDeleteIdx = cursor.getColumnIndexOrThrow("on_delete")
                while (cursor.moveToNext()) add(cursor.getString(tableIdx) to cursor.getString(onDeleteIdx))
            }
        }

    private companion object {
        /** 独立测试库名：绝不触碰生产库 yyx.db */
        const val DB_NAME = "yyx_migration_test.db"

        /** v1 库 room_master_table 的 identity_hash（实测自 V1.2 真实 v1 库 /tmp/yyx-lead-v1.db） */
        const val V1_IDENTITY_HASH = "23a1265e747c96a4d17f66c2e86765ec"

        /** v1 库完整 DDL（Room 2.6.1 为 schema v1 生成，实测自真实 v1 库） */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)",
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                "`author` TEXT, `summary` TEXT, `format` TEXT NOT NULL, `file_uri` TEXT NOT NULL, `charset` TEXT, " +
                "`cover_path` TEXT, `chapter_count` INTEGER NOT NULL, `word_count` INTEGER NOT NULL, " +
                "`file_mtime` INTEGER, `file_size` INTEGER, `last_read_at` INTEGER, `added_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_books_file_uri` ON `books` (`file_uri`)",
            "CREATE TABLE IF NOT EXISTS `chapters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`book_id` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                "`start_offset` INTEGER NOT NULL, `end_offset` INTEGER NOT NULL, `char_count` INTEGER NOT NULL, " +
                "`epub_href` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_chapters_book_id_idx` ON `chapters` (`book_id`, `idx`)",
            "CREATE TABLE IF NOT EXISTS `reading_progress` (`book_id` INTEGER NOT NULL, `chapter_idx` INTEGER NOT NULL, " +
                "`char_offset` INTEGER NOT NULL, `percent` REAL NOT NULL, `source` TEXT NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`book_id`))",
            "CREATE TABLE IF NOT EXISTS `scan_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`tree_uri` TEXT NOT NULL, `display_name` TEXT NOT NULL, `added_at` INTEGER NOT NULL, " +
                "`last_scan_at` INTEGER)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_folders_tree_uri` ON `scan_folders` (`tree_uri`)",
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`book_id` INTEGER NOT NULL, `chapter_idx` INTEGER NOT NULL, `char_offset` INTEGER NOT NULL, " +
                "`excerpt` TEXT, `created_at` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_bookmarks_book_id` ON `bookmarks` (`book_id`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
        )

        /** 种子数据：2 书 + 3 章 + 2 进度(read/listen) + 1 书签 + 1 扫描文件夹 */
        val SEED_SQL = listOf(
            "INSERT INTO `books` (`id`, `title`, `author`, `summary`, `format`, `file_uri`, `charset`, " +
                "`cover_path`, `chapter_count`, `word_count`, `file_mtime`, `file_size`, `last_read_at`, " +
                "`added_at`, `updated_at`) VALUES (1, '斗破苍穹', '天蚕土豆', NULL, 'txt', 'content://legacy/book-1', " +
                "NULL, NULL, 2, 2000, NULL, NULL, 1500, 1000, 1000)",
            "INSERT INTO `books` (`id`, `title`, `author`, `summary`, `format`, `file_uri`, `charset`, " +
                "`cover_path`, `chapter_count`, `word_count`, `file_mtime`, `file_size`, `last_read_at`, " +
                "`added_at`, `updated_at`) VALUES (2, '诡秘之主', NULL, NULL, 'epub', 'content://legacy/book-2', " +
                "NULL, NULL, 1, 3000, NULL, NULL, NULL, 2000, 2000)",
            "INSERT INTO `chapters` (`id`, `book_id`, `idx`, `title`, `start_offset`, `end_offset`, `char_count`, " +
                "`epub_href`) VALUES (1, 1, 0, '第一章 陨落的天才', 0, 100, 100, NULL)",
            "INSERT INTO `chapters` (`id`, `book_id`, `idx`, `title`, `start_offset`, `end_offset`, `char_count`, " +
                "`epub_href`) VALUES (2, 1, 1, '第二章 斗气大陆', 100, 250, 150, NULL)",
            "INSERT INTO `chapters` (`id`, `book_id`, `idx`, `title`, `start_offset`, `end_offset`, `char_count`, " +
                "`epub_href`) VALUES (3, 2, 0, '第一章 绯红', 0, 80, 80, 'text/0001.xhtml')",
            "INSERT INTO `reading_progress` (`book_id`, `chapter_idx`, `char_offset`, `percent`, `source`, " +
                "`updated_at`) VALUES (1, 1, 120, 0.4, 'read', 1500)",
            "INSERT INTO `reading_progress` (`book_id`, `chapter_idx`, `char_offset`, `percent`, `source`, " +
                "`updated_at`) VALUES (2, 0, 0, 0.0, 'listen', 1800)",
            "INSERT INTO `bookmarks` (`id`, `book_id`, `chapter_idx`, `char_offset`, `excerpt`, `created_at`) " +
                "VALUES (1, 1, 0, 12, '三十年河东', 1700)",
            "INSERT INTO `scan_folders` (`id`, `tree_uri`, `display_name`, `added_at`, `last_scan_at`) VALUES (1, " +
                "'content://com.android.externalstorage.documents/tree/primary%3ADownload%2Fnovels', 'novels', 500, NULL)",
        )
    }
}
