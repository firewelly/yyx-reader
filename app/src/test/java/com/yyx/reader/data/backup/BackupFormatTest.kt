package com.yyx.reader.data.backup

import com.yyx.reader.core.datastore.ListenSettings
import com.yyx.reader.core.datastore.ReaderSettings
import com.yyx.reader.core.domain.model.BackupSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式纯逻辑单测（不触达 org.json / Android API）：
 * schema 版本校验、设置映射、列过滤、导入计划、空备份判定、摘要统计。
 */
class BackupFormatTest {

    // ---------- schema 版本 ----------

    @Test
    fun `schema 版本校验`() {
        assertEquals(BackupFormat.SchemaCheck.OK, BackupFormat.checkSchema(1))
        assertEquals(BackupFormat.SchemaCheck.TOO_NEW, BackupFormat.checkSchema(2))
        assertEquals(BackupFormat.SchemaCheck.TOO_NEW, BackupFormat.checkSchema(99))
        assertEquals(BackupFormat.SchemaCheck.INVALID, BackupFormat.checkSchema(0))
        assertEquals(BackupFormat.SchemaCheck.INVALID, BackupFormat.checkSchema(-1))
        assertEquals(BackupFormat.SchemaCheck.INVALID, BackupFormat.checkSchema(null))
    }

    @Test
    fun `默认备份文件名符合格式`() {
        val name = BackupFormat.defaultFileName(0L)
        assertTrue(name, name.matches(Regex("""yyx-backup-\d{8}-\d{4}\.json""")))
        assertTrue(name.endsWith(BackupFormat.FILE_NAME_SUFFIX))
    }

    // ---------- 设置映射 ----------

    @Test
    fun `设置映射-完整备份覆盖当前值`() {
        val backup = BackupFormat.BackupSettings(
            fontSizeSp = 24,
            lineSpacingDp = 12,
            theme = "NIGHT",
            ttsRate = 1.5f,
            ttsEngine = "com.reecedunn.espeak",
            ttsEngineSpecified = true,
        )
        val baseReader = ReaderSettings()
        val reader = backup.toReaderSettings(baseReader)
        assertEquals(24, reader.fontSizeSp)
        assertEquals(12, reader.lineSpacingDp)
        assertEquals("NIGHT", reader.theme)

        val listen = backup.toListenSettings(ListenSettings())
        assertEquals(1.5f, listen.rate, 0.0001f)
        assertEquals("com.reecedunn.espeak", listen.enginePackage)
    }

    @Test
    fun `设置映射-缺字段保留当前值`() {
        val backup = BackupFormat.BackupSettings()
        val baseReader = ReaderSettings(fontSizeSp = 20, lineSpacingDp = 8, theme = "SEPIA")
        val reader = backup.toReaderSettings(baseReader)
        assertEquals(20, reader.fontSizeSp)
        assertEquals(8, reader.lineSpacingDp)
        assertEquals("SEPIA", reader.theme)

        val listen = backup.toListenSettings(ListenSettings(rate = 2.0f, enginePackage = "x"))
        assertEquals(2.0f, listen.rate, 0.0001f)
        assertEquals("x", listen.enginePackage)
    }

    @Test
    fun `设置映射-越界值收敛到合法区间`() {
        val backup = BackupFormat.BackupSettings(fontSizeSp = 99, lineSpacingDp = -5, ttsRate = 9.0f)
        val reader = backup.toReaderSettings(ReaderSettings())
        assertEquals(32, reader.fontSizeSp)
        assertEquals(0, reader.lineSpacingDp)
        assertEquals(3.0f, backup.toListenSettings(ListenSettings()).rate, 0.0001f)
    }

    @Test
    fun `设置映射-显式 null 引擎表示恢复自动`() {
        val backup = BackupFormat.BackupSettings(ttsEngine = null, ttsEngineSpecified = true)
        val listen = backup.toListenSettings(ListenSettings(enginePackage = "com.x"))
        assertNull(listen.enginePackage)
    }

    @Test
    fun `设置映射-ttsPreferredOutput 缺字段保留当前值`() {
        val current = ListenSettings(rate = 1.5f, forceSpeaker = true, preferredOutput = "t8:AA:BB")
        val merged = BackupFormat.BackupSettings(
            ttsPreferredOutputSpecified = false, // 旧备份没有该字段
        ).toListenSettings(current)
        assertEquals("t8:AA:BB", merged.preferredOutput)
        assertEquals(true, merged.forceSpeaker)
    }

    @Test
    fun `设置映射-ttsPreferredOutput 显式 null 表示恢复跟随系统`() {
        val current = ListenSettings(preferredOutput = "t4")
        val merged = BackupFormat.BackupSettings(
            ttsPreferredOutput = null,
            ttsPreferredOutputSpecified = true, // 新备份显式写了 null
        ).toListenSettings(current)
        assertEquals(null, merged.preferredOutput)
    }

    @Test
    fun `设置映射-ttsPreferredOutput 有值覆盖当前值`() {
        val current = ListenSettings(preferredOutput = "t4")
        val merged = BackupFormat.BackupSettings(
            ttsPreferredOutput = "t8:CC:DD",
            ttsPreferredOutputSpecified = true,
        ).toListenSettings(current)
        assertEquals("t8:CC:DD", merged.preferredOutput)
    }

    // ---------- 列过滤 ----------

    @Test
    fun `列过滤-丢弃目标表不存在的列`() {
        val row = linkedMapOf<String, Any?>("id" to 1L, "title" to "书", "future_col" to "x")
        val selection = BackupFormat.usableColumns(row, setOf("id", "title"))
        assertEquals(listOf("id", "title"), selection.columns)
        assertEquals(1, selection.skipped)
    }

    // ---------- 导入计划 ----------

    private fun content(
        settings: BackupFormat.BackupSettings? = null,
        tables: Map<String, List<BackupRow>> = emptyMap(),
    ) = BackupFormat.BackupContent(settings, tables)

    private val bookRow: BackupRow = linkedMapOf("id" to 1L, "title" to "测试书", "format" to "txt")
    private val chapterRow: BackupRow = linkedMapOf("id" to 10L, "book_id" to 1L, "idx" to 0)
    private val tagRow: BackupRow = linkedMapOf("id" to 3L, "name" to "玄幻")

    @Test
    fun `导入计划-v1 库无 tags 表时跳过而不失败`() {
        val plan = BackupFormat.planImport(
            content(
                tables = linkedMapOf(
                    "books" to listOf(bookRow),
                    "chapters" to listOf(chapterRow),
                    "tags" to listOf(tagRow),
                    "book_tags" to listOf(linkedMapOf("book_id" to 1L, "tag_id" to 3L)),
                ),
            ),
            existingColumns = mapOf(
                "books" to setOf("id", "title", "format"),
                "chapters" to setOf("id", "book_id", "idx"),
                "reading_progress" to setOf("book_id"),
                "bookmarks" to setOf("id"),
                "scan_folders" to setOf("id"),
            ),
        )
        assertEquals(listOf("tags", "book_tags"), plan.skippedTables)
        assertEquals(1, plan.rowsByTable["books"]?.size)
        assertEquals(1, plan.rowsByTable["chapters"]?.size)
        // 备份中缺 reading_progress / bookmarks / scan_folders 键 → 保留本机数据，不清空不插入（P2-2）
        assertEquals(listOf("reading_progress", "bookmarks", "scan_folders"), plan.missingTables)
        assertTrue(plan.ignoredTables.isEmpty())
        // 只回填「备份有键」的表，且顺序遵循父表 → 子表
        assertEquals(listOf("books", "chapters"), plan.rowsByTable.keys.toList())
    }

    @Test
    fun `导入计划-缺表键保留本机数据`() {
        val plan = BackupFormat.planImport(
            content(tables = mapOf("books" to listOf(bookRow))),
            existingColumns = mapOf(
                "books" to setOf("id", "title", "format"),
                "chapters" to setOf("id", "book_id", "idx"),
            ),
        )
        // 备份缺 chapters 键 → 不进入 rowsByTable（不参与清空/回填），进入 missingTables
        assertFalse(plan.rowsByTable.containsKey("chapters"))
        assertEquals(listOf("chapters"), plan.missingTables)
        assertTrue(plan.rowsByTable.containsKey("books"))
    }

    @Test
    fun `导入计划-有键但空数组视为清空`() {
        val plan = BackupFormat.planImport(
            content(tables = mapOf("books" to emptyList())),
            existingColumns = mapOf("books" to setOf("id")),
        )
        // 有键（空数组）→ 进入 rowsByTable（触发清空），且不算缺表
        assertTrue(plan.rowsByTable.containsKey("books"))
        assertTrue(plan.rowsByTable.getValue("books").isEmpty())
        assertTrue(plan.missingTables.isEmpty())
    }

    @Test
    fun `导入计划-有键且有行正常回填`() {
        val plan = BackupFormat.planImport(
            content(tables = mapOf("books" to listOf(bookRow), "chapters" to listOf(chapterRow))),
            existingColumns = mapOf(
                "books" to setOf("id", "title", "format"),
                "chapters" to setOf("id", "book_id", "idx"),
            ),
        )
        assertEquals(1, plan.rowsByTable.getValue("books").size)
        assertEquals(1, plan.rowsByTable.getValue("chapters").size)
        assertTrue(plan.missingTables.isEmpty())
    }

    @Test
    fun `导入计划-未知列跳过并计数`() {
        val row = linkedMapOf<String, Any?>(
            "id" to 1L,
            "title" to "书",
            "future_col" to "x",
            "another" to null,
        )
        val plan = BackupFormat.planImport(
            content(tables = mapOf("books" to listOf(row))),
            existingColumns = mapOf("books" to setOf("id", "title")),
        )
        val planned = plan.rowsByTable.getValue("books").single()
        assertEquals(listOf("id", "title"), planned.keys.toList())
        assertEquals(2, plan.skippedColumns)
    }

    @Test
    fun `导入计划-整行无可识别列时保留空行供报错`() {
        val row = linkedMapOf<String, Any?>("future_col" to 1L)
        val plan = BackupFormat.planImport(
            content(tables = mapOf("books" to listOf(row))),
            existingColumns = mapOf("books" to setOf("id")),
        )
        assertTrue(plan.rowsByTable.getValue("books").single().isEmpty())
        assertEquals(1, plan.skippedColumns)
    }

    @Test
    fun `导入计划-备份中未知表被忽略并计数`() {
        val plan = BackupFormat.planImport(
            content(tables = linkedMapOf("books" to listOf(bookRow), "future_table" to listOf(bookRow))),
            existingColumns = mapOf("books" to setOf("id", "title", "format")),
        )
        assertEquals(listOf("future_table"), plan.ignoredTables)
        assertFalse(plan.rowsByTable.containsKey("future_table"))
    }

    @Test
    fun `导入计划-空表不产生行`() {
        val plan = BackupFormat.planImport(
            content(tables = mapOf("books" to emptyList())),
            existingColumns = mapOf("books" to setOf("id")),
        )
        assertTrue(plan.rowsByTable.getValue("books").isEmpty())
        assertEquals(0, plan.skippedColumns)
    }

    // ---------- 重复主键（P2-3） ----------

    @Test
    fun `重复主键-单表重复计数`() {
        val rows = listOf(
            linkedMapOf<String, Any?>("id" to 1L, "title" to "A"),
            linkedMapOf<String, Any?>("id" to 2L, "title" to "B"),
            linkedMapOf<String, Any?>("id" to 1L, "title" to "A2"),
        )
        val report = BackupFormat.countDuplicatePrimaryKeys(mapOf("books" to rows))
        assertEquals(1, report.duplicateRows)
        assertEquals(mapOf("books" to 1), report.byTable)
    }

    @Test
    fun `重复主键-多表累计`() {
        val report = BackupFormat.countDuplicatePrimaryKeys(
            mapOf(
                "books" to listOf(
                    linkedMapOf<String, Any?>("id" to 1L),
                    linkedMapOf<String, Any?>("id" to 1L),
                ),
                "reading_progress" to listOf(
                    linkedMapOf<String, Any?>("book_id" to 7L),
                    linkedMapOf<String, Any?>("book_id" to 7L),
                    linkedMapOf<String, Any?>("book_id" to 7L),
                ),
            ),
        )
        assertEquals(3, report.duplicateRows)
        assertEquals(mapOf("books" to 1, "reading_progress" to 2), report.byTable)
    }

    @Test
    fun `重复主键-复合主键 book_tags`() {
        val report = BackupFormat.countDuplicatePrimaryKeys(
            mapOf(
                "book_tags" to listOf(
                    linkedMapOf<String, Any?>("book_id" to 1L, "tag_id" to 2L),
                    linkedMapOf<String, Any?>("book_id" to 1L, "tag_id" to 2L), // 重复
                    linkedMapOf<String, Any?>("book_id" to 1L, "tag_id" to 3L), // 不同
                ),
            ),
        )
        assertEquals(1, report.duplicateRows)
        assertEquals(mapOf("book_tags" to 1), report.byTable)
    }

    @Test
    fun `重复主键-无重复或缺主键列时不计`() {
        val report = BackupFormat.countDuplicatePrimaryKeys(
            mapOf(
                "books" to listOf(
                    linkedMapOf<String, Any?>("id" to 1L),
                    linkedMapOf<String, Any?>("id" to 2L),
                ),
                // 缺 id 列无法判定主键，不应误报重复
                "chapters" to listOf(
                    linkedMapOf<String, Any?>("book_id" to 1L),
                    linkedMapOf<String, Any?>("book_id" to 1L),
                ),
                "tags" to emptyList(),
            ),
        )
        assertEquals(0, report.duplicateRows)
        assertTrue(report.byTable.isEmpty())
    }

    @Test
    fun `主键定义覆盖全部备份表`() {
        assertEquals(BackupFormat.TABLES.toSet(), BackupFormat.PRIMARY_KEYS.keys)
        assertEquals(listOf("book_id", "tag_id"), BackupFormat.PRIMARY_KEYS.getValue("book_tags"))
        assertEquals(listOf("book_id"), BackupFormat.PRIMARY_KEYS.getValue("reading_progress"))
    }

    @Test
    fun `上限常量与 bytesWithinLimit（V1_4 提升到 64MB）`() {
        assertEquals(64L * 1024 * 1024, BackupFormat.MAX_FILE_BYTES)
        assertTrue(BackupFormat.bytesWithinLimit(0))
        assertTrue(BackupFormat.bytesWithinLimit(BackupFormat.MAX_FILE_BYTES))
        assertEquals(false, BackupFormat.bytesWithinLimit(BackupFormat.MAX_FILE_BYTES + 1))
    }

    // ---------- 核心表键 / 表顺序校验（V1.4） ----------

    @Test
    fun `缺核心表键拒绝导入`() {
        // 完整 7 表 → 通过
        assertEquals(null, BackupFormat.validateCoreTables(BackupFormat.TABLES))
        // 缺 chapters → 拒绝（错误详情里点名 chapters）
        val noChapters = BackupFormat.validateCoreTables(BackupFormat.TABLES - "chapters")
        assertEquals(BackupFormat.BackupError.MISSING_CORE, noChapters!!.error)
        assertTrue(noChapters.detail!!.contains("chapters"))
        // 缺 books → 拒绝
        val noBooks = BackupFormat.validateCoreTables(BackupFormat.TABLES - "books")
        assertTrue(noBooks!!.detail!!.contains("books"))
        // 两表都缺 → 详情同时点名
        val both = BackupFormat.validateCoreTables(listOf("bookmarks", "tags"))!!
        assertTrue(both.detail!!.contains("books") && both.detail!!.contains("chapters"))
    }

    @Test
    fun `表顺序校验要求 book_tags 晚于 books 与 tags`() {
        // 本应用导出顺序 → 通过
        assertEquals(null, BackupFormat.validateTableOrder(BackupFormat.TABLES))
        // 无 book_tags → 通过（其他顺序不限制）
        assertEquals(null, BackupFormat.validateTableOrder(listOf("chapters", "books")))
        // book_tags 早于 books → 拒绝
        val bad = listOf("book_tags", "books", "chapters", "tags")
        assertEquals(BackupFormat.BackupError.TABLE_ORDER, BackupFormat.validateTableOrder(bad)!!.error)
    }

    // ---------- book_tags 引用过滤（P2-4） ----------

    @Test
    fun `标签关联过滤-丢弃引用不存在书籍或标签的行`() {
        val rows = listOf(
            linkedMapOf<String, Any?>("book_id" to 1L, "tag_id" to 10L), // 有效
            linkedMapOf<String, Any?>("book_id" to 1L, "tag_id" to 99L), // 标签不存在
            linkedMapOf<String, Any?>("book_id" to 9L, "tag_id" to 10L), // 书籍不存在
            linkedMapOf<String, Any?>("book_id" to null, "tag_id" to 10L), // 缺 book_id
        )
        val filter = BackupFormat.filterValidBookTags(rows, bookIds = setOf(1L), tagIds = setOf(10L))
        assertEquals(1, filter.validRows.size)
        assertEquals(3, filter.skippedRows)
        assertEquals(1L, filter.validRows.single()["book_id"])
        assertEquals(10L, filter.validRows.single()["tag_id"])
    }

    @Test
    fun `标签关联过滤-全部有效或空列表`() {
        val allValid = listOf(
            linkedMapOf<String, Any?>("book_id" to 1L, "tag_id" to 10L),
            linkedMapOf<String, Any?>("book_id" to 2L, "tag_id" to 10L),
        )
        val ok = BackupFormat.filterValidBookTags(allValid, setOf(1L, 2L), setOf(10L))
        assertEquals(2, ok.validRows.size)
        assertEquals(0, ok.skippedRows)

        val empty = BackupFormat.filterValidBookTags(emptyList(), emptySet(), emptySet())
        assertTrue(empty.validRows.isEmpty())
        assertEquals(0, empty.skippedRows)
    }

    @Test
    fun `标签关联过滤-有效集合为空时全部跳过`() {
        val rows = listOf(
            linkedMapOf<String, Any?>("book_id" to 1L, "tag_id" to 10L),
            linkedMapOf<String, Any?>("book_id" to 2L, "tag_id" to 20L),
        )
        val filter = BackupFormat.filterValidBookTags(rows, bookIds = emptySet(), tagIds = setOf(10L, 20L))
        assertTrue(filter.validRows.isEmpty())
        assertEquals(2, filter.skippedRows)
    }

    // ---------- 空备份 ----------

    @Test
    fun `空备份判定-无设置且无任何行`() {
        assertTrue(BackupFormat.isEffectivelyEmpty(content()))
        assertTrue(
            BackupFormat.isEffectivelyEmpty(
                content(tables = mapOf("books" to emptyList(), "chapters" to emptyList())),
            ),
        )
        assertFalse(
            BackupFormat.isEffectivelyEmpty(
                content(tables = mapOf("books" to listOf(bookRow))),
            ),
        )
        // 只有设置的备份（空书库）应允许导入
        assertFalse(
            BackupFormat.isEffectivelyEmpty(
                content(settings = BackupFormat.BackupSettings(fontSizeSp = 18)),
            ),
        )
    }

    // ---------- 摘要 ----------

    @Test
    fun `摘要-总行数`() {
        val summary = BackupSummary(tableCounts = mapOf("books" to 2, "chapters" to 30))
        assertEquals(32, summary.totalRows)
        // 文案已多语言化（界面层用 backup_summary_rows 资源渲染），此处只验证数据
        assertTrue(summary.warnings.isEmpty())
    }

    // ---------- 表顺序（外键安全） ----------

    @Test
    fun `表顺序满足外键约束`() {
        // 清空：子表在父表之前
        assertTrue(BackupFormat.CLEAR_ORDER.indexOf("book_tags") < BackupFormat.CLEAR_ORDER.indexOf("tags"))
        assertTrue(BackupFormat.CLEAR_ORDER.indexOf("bookmarks") < BackupFormat.CLEAR_ORDER.indexOf("books"))
        assertTrue(BackupFormat.CLEAR_ORDER.indexOf("reading_progress") < BackupFormat.CLEAR_ORDER.indexOf("books"))
        assertTrue(BackupFormat.CLEAR_ORDER.indexOf("chapters") < BackupFormat.CLEAR_ORDER.indexOf("books"))
        // 回填：父表在子表之前
        assertTrue(BackupFormat.INSERT_ORDER.indexOf("books") < BackupFormat.INSERT_ORDER.indexOf("chapters"))
        assertTrue(BackupFormat.INSERT_ORDER.indexOf("books") < BackupFormat.INSERT_ORDER.indexOf("bookmarks"))
        assertTrue(BackupFormat.INSERT_ORDER.indexOf("tags") < BackupFormat.INSERT_ORDER.indexOf("book_tags"))
        // 两套顺序覆盖同一批表
        assertEquals(BackupFormat.TABLES.toSet(), BackupFormat.INSERT_ORDER.toSet())
        assertEquals(BackupFormat.TABLES.toSet(), BackupFormat.CLEAR_ORDER.toSet())
    }

    // ---------- 16MB 上限判定（V1.3 收尾 P2-1：导出与导入一致） ----------

    @Test
    fun `备份大小上限判定`() {
        assertTrue(BackupFormat.bytesWithinLimit(0))
        assertTrue(BackupFormat.bytesWithinLimit(BackupFormat.MAX_FILE_BYTES))
        assertFalse(BackupFormat.bytesWithinLimit(BackupFormat.MAX_FILE_BYTES + 1))
    }

    // ---------- 听书强制外放设置合并（V1.3 收尾 P2-4） ----------

    @Test
    fun `备份中的强制外放设置按显式标记合并`() {
        val base = ListenSettings(rate = 1.0f, enginePackage = "a.tts", forceSpeaker = false)
        // 旧备份无该字段 → 保留当前值
        assertEquals(false, BackupFormat.BackupSettings().toListenSettings(base).forceSpeaker)
        // 显式 false → 覆盖为 false（即便当前为 true）
        val forcedOn = base.copy(forceSpeaker = true)
        assertEquals(
            false,
            BackupFormat.BackupSettings(ttsForceSpeaker = false, ttsForceSpeakerSpecified = true)
                .toListenSettings(forcedOn).forceSpeaker,
        )
        // 显式 true → 覆盖为 true
        assertEquals(
            true,
            BackupFormat.BackupSettings(ttsForceSpeaker = true, ttsForceSpeakerSpecified = true)
                .toListenSettings(base).forceSpeaker,
        )
        // 不影响 rate / enginePackage
        val merged = BackupFormat.BackupSettings(ttsForceSpeaker = true, ttsForceSpeakerSpecified = true)
            .toListenSettings(base)
        assertEquals(1.0f, merged.rate, 0.0001f)
        assertEquals("a.tts", merged.enginePackage)
    }
}
