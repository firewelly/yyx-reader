package com.yyx.reader.data.backup

import com.yyx.reader.R
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yyx.reader.BuildConfig
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.datastore.ReaderSettingsRepository
import com.yyx.reader.core.domain.model.BackupResult
import com.yyx.reader.core.domain.model.BackupSummary
import com.yyx.reader.core.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份 / 恢复实现（PRD F-12）。
 *
 * 导出：直接用 `SELECT * FROM <table>` 逐表读原始行（不用 DAO），
 * 先查 sqlite_master 判断表是否存在 —— 兼容尚未迁移出 tags / book_tags 的 v1 库。
 * 导入：单事务内按外键安全顺序清空后回填，显式保留原 id；失败整体回滚。
 * 文件 IO 全部走 Dispatchers.IO，错误统一转中文消息。
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: YYXDatabase,
    private val settingsRepository: ReaderSettingsRepository,
) : BackupRepository {

    override suspend fun export(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val db = database.openHelper.readableDatabase
            val existingTables = tableNames(db)
            val skippedTables = mutableListOf<String>()

            val readerSettings = settingsRepository.currentReaderSettings()
            val listen = settingsRepository.currentListenSettings()
            val settings = BackupFormat.BackupSettings(
                fontSizeSp = readerSettings.fontSizeSp,
                lineSpacingDp = readerSettings.lineSpacingDp,
                theme = readerSettings.theme,
                ttsRate = listen.rate,
                ttsEngine = listen.enginePackage,
                // 导出文件始终显式写出 ttsEngine（null = 自动），恢复时能还原「自动」
                ttsEngineSpecified = true,
                ttsForceSpeaker = listen.forceSpeaker,
                ttsForceSpeakerSpecified = true,
                // 设备选择器跨设备可能匹配不到（地址不同），但仍导出以保留用户意图，恢复后自然回退
                ttsPreferredOutput = listen.preferredOutput,
                ttsPreferredOutputSpecified = true,
            )
            val exportedAt = System.currentTimeMillis()

            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error(context.getString(R.string.backup_error_write_file))
            var byteCount = 0L
            val tableCounts = LinkedHashMap<String, Int>()
            try {
                output.use { stream ->
                    // V1.4：事务内**流式**写出（逐表逐行），多表快照一致 + 内存有界 + 边写边校验 64MB 上限
                    database.withTransaction {
                        val result = BackupJsonWriter.write(
                            out = stream,
                            exportedAt = exportedAt,
                            versionName = BuildConfig.VERSION_NAME,
                            settings = settings,
                            tables = BackupFormat.TABLES,
                            streamRows = { table, emit ->
                                if (table in existingTables) {
                                    readRowsStreaming(db, table, emit)
                                } else {
                                    // 本机无该表（v1 库无 tags/book_tags）：写空数组，行为与 V1.3 一致
                                    skippedTables += table
                                }
                            },
                            maxBytes = BackupFormat.MAX_FILE_BYTES,
                        )
                        byteCount = result.byteCount
                        tableCounts.putAll(result.tableCounts)
                    }
                }
            } catch (e: BackupJsonWriter.SizeLimitExceeded) {
                // SAF CreateDocument 已创建空文件：失败时删掉，避免用户目录残留 0 字节 .json
                runCatching { context.contentResolver.delete(uri, null, null) }
                error(context.getString(R.string.backup_error_export_too_large, BackupFormat.MAX_FILE_BYTES / 1024 / 1024))
            }

            BackupResult.Success(
                BackupSummary(
                    byteCount = byteCount,
                    exportedAt = exportedAt,
                    settingsIncluded = true,
                    tableCounts = tableCounts,
                    skippedTables = skippedTables,
                ),
            )
        }.getOrElse { error -> BackupResult.Failure(context.getString(R.string.backup_error_export_failed, backupErrorText(error))) }
    }

    override suspend fun import(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            // SAF 输入流不可 seek：两遍各自打开（pass1 只扫描元信息，pass2 逐行回填）
            val open: () -> InputStream = { context.contentResolver.openInputStream(uri) ?: error(context.getString(R.string.backup_error_read_file)) }

            // ---- pass 1：schema / 核心表 / 空备份 / 表顺序（全部在任何清空之前）----
            val scan = BackupStreamReader.scan(open, BackupFormat.MAX_FILE_BYTES)
            when (BackupFormat.checkSchema(scan.schemaVersion)) {
                BackupFormat.SchemaCheck.TOO_NEW -> error(
                    context.getString(R.string.backup_error_schema_too_new, scan.schemaVersion ?: 0),
                )
                BackupFormat.SchemaCheck.INVALID -> error(context.getString(R.string.backup_error_schema_missing))
                BackupFormat.SchemaCheck.OK -> Unit
            }
            // V1.4（Reviewer P2-2）：缺 books/chapters 视为损坏备份，直接拒绝（本机数据不动）
            BackupFormat.validateCoreTables(scan.tableKeys)?.let { throw it }
            BackupFormat.validateTableOrder(scan.tableKeys)?.let { throw it }
            if (scan.settings == null && scan.rowCounts.values.all { it == 0 }) {
                error(context.getString(R.string.backup_error_no_data))
            }

            val db = database.openHelper.writableDatabase
            val existingTables = tableNames(db)
            val existingColumns = existingTables.associateWith { columnNames(db, it) }

            // 复用纯函数生成计划（不预聚合行，只取「缺键/本机无/未知表」与「有键且本机存在的表」）
            val plan = BackupFormat.planImport(
                BackupFormat.BackupContent(scan.settings, scan.tableKeys.associateWith { emptyList() }),
                existingColumns,
            )
            val tablesToImport: Set<String> = plan.rowsByTable.keys

            val actualCounts = LinkedHashMap<String, Int>()
            var skippedColumns = 0
            var skippedBookTags = 0
            var duplicateRows = 0
            var effectiveBookIds: Set<Long> = emptySet()
            var effectiveTagIds: Set<Long> = emptySet()

            // ---- 单事务：先清空「有键」表（子→父），再流式回填（父→子）----
            database.withTransaction {
                BackupFormat.CLEAR_ORDER.forEach { table ->
                    if (table in tablesToImport) db.execSQL("DELETE FROM `$table`")
                }
                // 重复主键检测：每表维护已见主键集合（行级内存，O(行数) 的小字符串键）
                val seenKeys = BackupFormat.PRIMARY_KEYS.mapValues { HashSet<String>() }
                BackupStreamReader.streamRows(
                    open = open,
                    onTableStart = { table ->
                        if (table == "book_tags") {
                            // book_tags 之前 books/tags 已回填：以库内实际 id 为有效集合（P2-4 预过滤）
                            effectiveBookIds = queryLongs(db, "SELECT id FROM books").toSet()
                            effectiveTagIds = queryLongs(db, "SELECT id FROM tags").toSet()
                        }
                    },
                    onRow = row@{ table, row ->
                        val columns = existingColumns[table]
                        if (columns == null || table !in tablesToImport) {
                            // 本机无该表 / 该表缺键未导入 → 列全部跳过
                            skippedColumns += row.size
                            return@row
                        }
                        val selection = BackupFormat.usableColumns(row, columns)
                        skippedColumns += selection.skipped
                        if (selection.columns.isEmpty()) return@row
                        val usable = LinkedHashMap<String, Any?>(selection.columns.size)
                        selection.columns.forEach { usable[it] = row[it] }

                        // 备份内重复主键（后者覆盖前者）
                        BackupFormat.PRIMARY_KEYS[table]?.let { keys ->
                            val seen = seenKeys[table]
                            if (seen != null && keys.all { usable.containsKey(it) }) {
                                val key = keys.joinToString("\u0000") { column ->
                                    usable[column]?.let { "${it.javaClass.simpleName}:$it" } ?: "null"
                                }
                                if (!seen.add(key)) duplicateRows++
                            }
                        }

                        // book_tags 预过滤（唯一带 DB 外键的表，孤儿关联会让整次恢复失败）
                        if (table == "book_tags") {
                            val bookId = (usable["book_id"] as? Number)?.toLong()
                            val tagId = (usable["tag_id"] as? Number)?.toLong()
                            if (bookId == null || tagId == null ||
                                bookId !in effectiveBookIds || tagId !in effectiveTagIds
                            ) {
                                skippedBookTags++
                                return@row
                            }
                        }

                        val insertColumns = usable.keys.toList()
                        val sql = "INSERT OR REPLACE INTO $table " +
                            "(${insertColumns.joinToString(",")}) VALUES " +
                            "(${insertColumns.joinToString(",") { "?" }})"
                        db.execSQL(sql, insertColumns.map { usable[it] }.toTypedArray())
                    },
                )
                tablesToImport.forEach { table -> actualCounts[table] = countRows(db, table) }
            }

            val warnings = mutableListOf<String>()

            plan.missingTables.forEach { table ->
                warnings += context.getString(R.string.backup_warn_table_kept, table)
            }
            if (skippedBookTags > 0) {
                warnings += context.getString(R.string.backup_warn_booktags_skipped, skippedBookTags)
            }
            if (duplicateRows > 0) {
                warnings += context.getString(R.string.backup_warn_duplicate_rows, duplicateRows)
            }
            warnings += referenceIntegrityWarnings(db, existingTables)
            warnings += sourceAccessibilityWarnings(db, existingTables)
            if (plan.skippedTables.isNotEmpty()) {
                warnings += context.getString(R.string.backup_warn_tables_skipped, plan.skippedTables.joinToString("/"))
            }
            if (plan.ignoredTables.isNotEmpty()) {
                warnings += context.getString(R.string.backup_warn_unknown_tables, plan.ignoredTables.size)
            }
            if (skippedColumns > 0) {
                warnings += context.getString(R.string.backup_warn_unknown_columns, skippedColumns)
            }

            // 设置恢复与数据导入解耦：设置失败不影响数据，如实汇报为部分成功
            var settingsApplied = false
            val backupSettings = scan.settings
            if (backupSettings == null) {
                warnings += context.getString(R.string.backup_warn_no_settings)
            } else {
                var readerOk = false
                var listenOk = false
                runCatching {
                    settingsRepository.applyReaderSettings(
                        backupSettings.toReaderSettings(settingsRepository.currentReaderSettings()),
                    )
                    readerOk = true
                }.onFailure { warnings += context.getString(R.string.backup_warn_reader_settings_failed, messageOf(it)) }
                runCatching {
                    settingsRepository.applyListenSettings(
                        backupSettings.toListenSettings(settingsRepository.currentListenSettings()),
                    )
                    listenOk = true
                }.onFailure { warnings += context.getString(R.string.backup_warn_listen_settings_failed, messageOf(it)) }
                settingsApplied = readerOk && listenOk
            }

            BackupResult.Success(
                BackupSummary(
                    byteCount = scan.byteCount,
                    exportedAt = scan.exportedAt,
                    settingsIncluded = settingsApplied,
                    tableCounts = actualCounts,
                    skippedTables = plan.skippedTables,
                    skippedColumns = skippedColumns,
                    warnings = warnings,
                ),
            )
        }.getOrElse { error -> BackupResult.Failure(context.getString(R.string.backup_error_import_failed, backupErrorText(error))) }
    }

    // ---------- 导入辅助（P2-3 / P2-4 / P2-6 / 待办 8） ----------

    /** P2-4：导入后引用完整性校验（只告警、不删数据） */
    private fun referenceIntegrityWarnings(
        db: SupportSQLiteDatabase,
        existingTables: Set<String>,
    ): List<String> {
        val warnings = mutableListOf<String>()
        fun orphans(table: String, where: String): Int =
            if (table in existingTables) countScalar(db, "SELECT COUNT(*) FROM $table WHERE $where") else 0

        val chapters = orphans("chapters", "book_id NOT IN (SELECT id FROM books)")
        if (chapters > 0) warnings += context.getString(R.string.backup_warn_dangling_chapters, chapters)
        val progress = orphans("reading_progress", "book_id NOT IN (SELECT id FROM books)")
        if (progress > 0) warnings += context.getString(R.string.backup_warn_dangling_progress, progress)
        val bookmarks = orphans("bookmarks", "book_id NOT IN (SELECT id FROM books)")
        if (bookmarks > 0) warnings += context.getString(R.string.backup_warn_dangling_bookmarks, bookmarks)
        if ("book_tags" in existingTables && "books" in existingTables && "tags" in existingTables) {
            val bookTags = countScalar(
                db,
                "SELECT COUNT(*) FROM book_tags WHERE book_id NOT IN (SELECT id FROM books) " +
                    "OR tag_id NOT IN (SELECT id FROM tags)",
            )
            if (bookTags > 0) warnings += context.getString(R.string.backup_warn_dangling_booktags, bookTags)
        }
        return warnings
    }

    /** AGENTS 待办 8：抽样（≤50）检查 books.file_uri / scan_folders.tree_uri 是否仍可访问 */
    private fun sourceAccessibilityWarnings(
        db: SupportSQLiteDatabase,
        existingTables: Set<String>,
    ): List<String> {
        val warnings = mutableListOf<String>()
        if ("books" in existingTables) {
            val uris = queryStrings(db, "SELECT file_uri FROM books ORDER BY id LIMIT $MAX_SOURCE_SAMPLE")
            val unreadable = uris.count { !isUriReadable(it) }
            if (unreadable > 0) {
                warnings += context.getString(R.string.backup_warn_sources_unreadable, unreadable)
            }
        }
        if ("scan_folders" in existingTables) {
            val uris = queryStrings(
                db,
                "SELECT tree_uri FROM scan_folders ORDER BY id LIMIT $MAX_SOURCE_SAMPLE",
            )
            val unreadable = uris.count { !isUriReadable(it) }
            if (unreadable > 0) {
                warnings += context.getString(R.string.backup_warn_folders_unreadable, unreadable)
            }
        }
        return warnings
    }

    /** content:// 与 file:// 都试（file scheme 由 ContentResolver 内置支持）；tree URI 退化为 DocumentFile */
    private fun isUriReadable(uriText: String): Boolean {
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return false
        val opened = runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
        if (opened) return true
        return runCatching {
            DocumentFile.fromTreeUri(context, uri)?.canRead() == true
        }.getOrDefault(false)
    }

    private fun queryStrings(db: SupportSQLiteDatabase, sql: String): List<String> {
        val values = mutableListOf<String>()
        db.query(SimpleSQLiteQuery(sql)).use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { values += it }
        }
        return values
    }

    private fun queryLongs(db: SupportSQLiteDatabase, sql: String): List<Long> {
        val values = mutableListOf<Long>()
        db.query(SimpleSQLiteQuery(sql)).use { cursor ->
            while (cursor.moveToNext()) values += cursor.getLong(0)
        }
        return values
    }

    /** 备份 rows 中某表的主键 id 集合（books / tags 均为自增 id 列） */
    private fun idSetOf(rows: List<BackupRow>): Set<Long> =
        rows.mapNotNullTo(HashSet(rows.size)) { (it["id"] as? Number)?.toLong() }

    private fun countRows(db: SupportSQLiteDatabase, table: String): Int =
        countScalar(db, "SELECT COUNT(*) FROM `$table`")

    private fun countScalar(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(SimpleSQLiteQuery(sql)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private companion object {
        /** 书源 / 扫描目录可访问性抽检上限（本） */
        const val MAX_SOURCE_SAMPLE = 50
    }

    // ---------- SQLite 原始读取（不依赖 DAO，兼容 v1 库） ----------

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type='table'")).use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> = buildSet {
        db.query(SimpleSQLiteQuery("PRAGMA table_info(`$table`)")).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }

    /** V1.4：逐行流式读取某表并回调（不把整表读进内存） */
    private fun readRowsStreaming(
        db: SupportSQLiteDatabase,
        table: String,
        emit: (BackupRow) -> Unit,
    ) {
        db.query(SimpleSQLiteQuery("SELECT * FROM `$table`")).use { cursor ->
            val columnNames = cursor.columnNames
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, Any?>(columnNames.size)
                columnNames.forEachIndexed { index, name ->
                    row[name] = when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        // 当前 schema 无 BLOB 列；出现即说明格式不兼容，明确失败而非静默丢数据
                        else -> error(context.getString(R.string.backup_error_binary_column, table, name))
                    }
                }
                emit(row)
            }
        }
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    /** 备份异常 → 本地化文案（类型化错误优先，其余回退原始 message） */
    private fun backupErrorText(t: Throwable): String = when (t) {
        is BackupFormat.SizeLimitException ->
            context.getString(R.string.backup_error_too_large, t.maxBytes / 1024 / 1024)
        is BackupFormat.BackupException -> when (t.error) {
            BackupFormat.BackupError.MISSING_CORE -> context.getString(R.string.backup_error_missing_core, t.detail.orEmpty())
            BackupFormat.BackupError.TABLE_ORDER -> context.getString(R.string.backup_error_table_order)
            BackupFormat.BackupError.NOT_JSON -> context.getString(R.string.backup_error_not_json)
            BackupFormat.BackupError.READ_FAILED -> context.getString(R.string.backup_error_read_file)
            BackupFormat.BackupError.TABLE_NOT_ARRAY -> context.getString(R.string.backup_error_table_not_array, t.detail.orEmpty())
            BackupFormat.BackupError.ROW_NOT_OBJECT -> context.getString(R.string.backup_error_row_not_object)
        }
        else -> messageOf(t)
    }
}