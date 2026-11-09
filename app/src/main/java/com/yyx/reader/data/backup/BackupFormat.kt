package com.yyx.reader.data.backup

import com.yyx.reader.core.datastore.ListenSettings
import com.yyx.reader.core.datastore.ReaderSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 备份中的一行记录：列名 → 值（仅 SQLite 基础类型 Long / Double / String / null） */
typealias BackupRow = Map<String, Any?>

/**
 * 备份文件格式（schema v1）与纯 JVM 映射逻辑。
 *
 * 本文件**不含任何 Android API**（流式写出见 [BackupJsonWriter]，流式读取见 [BackupStreamReader]），
 * 因此 schema 校验、设置映射、导入计划等分支可在本地单元测试覆盖。
 *
 * 文件结构：
 * ```json
 * {"schema":1,"app":"com.yyx.reader","versionName":"0.1.0","exportedAt":<epochMs>,
 *  "settings":{"fontSizeSp":18,"lineSpacingDp":6,"theme":"DAY","ttsRate":1.0,"ttsEngine":null,
 *              "ttsForceSpeaker":false,"ttsPreferredOutput":null},
 *  "books":[{...}],"chapters":[{...}],"reading_progress":[{...}],"bookmarks":[{...}],
 *  "tags":[{...}],"book_tags":[{...}],"scan_folders":[{...}]}
 * ```
 * `ttsForceSpeaker`（V1.3 收尾新增）等设置字段均为**可选**：旧备份缺字段时保留当前设置，
 * 不升级 schema 版本（向后/向前兼容：旧 App 解码时忽略未知 settings 键）。
 * 行对象以列名为 key，数值 / null 原样保留（不做类型推断，避免精度损失）。
 */
object BackupFormat {

    /** 当前备份 schema 版本；文件 schema > 本值表示来自更高版本 App，拒绝导入 */
    const val SCHEMA_VERSION = 1

    const val APP_ID = "com.yyx.reader"
    const val MIME_TYPE = "application/json"
    const val FILE_NAME_PREFIX = "yyx-backup-"
    const val FILE_NAME_SUFFIX = ".json"

    /**
     * 备份文件大小上限（V1.4 流式化后提升到 64MB）：
     * 导出边写边校验、导入边读边校验，超限立即中止，不会把整串内容读进内存。
     */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** 超过大小上限（消息由界面层按语言映射，[maxBytes] 供文案参数） */
    class SizeLimitException(val maxBytes: Long) : Exception("Backup size limit exceeded ($maxBytes bytes)")

    /** 备份错误类型（界面层映射 strings.xml backup_error_*；[detail] 为补充参数） */
    enum class BackupError { MISSING_CORE, TABLE_ORDER, NOT_JSON, READ_FAILED, TABLE_NOT_ARRAY, ROW_NOT_OBJECT }

    /** 备份异常：携带错误类型与补充参数，文案由界面层按语言解析 */
    class BackupException(val error: BackupError, val detail: String? = null) :
        IllegalArgumentException("${error.name}${detail?.let { ": $it" } ?: ""}")

    /**
     * 核心表键校验（V1.4，Reviewer P2-2）：备份缺少 books 或 chapters 键视为损坏/截断，
     * 必须在**清空本机数据之前**拒绝，避免「缺表键保留本机 + 其它表覆盖」造成跨表 id 语义错配。
     *
     * @return null = 通过；否则为 [BackupException]（界面层映射中文/英文文案）
     */
    fun validateCoreTables(tableKeys: Collection<String>): BackupException? {
        val missing = CORE_TABLES.filter { it !in tableKeys }
        return if (missing.isEmpty()) null else {
            BackupException(BackupError.MISSING_CORE, missing.joinToString("/"))
        }
    }

    /**
     * 表顺序校验（V1.4 流式导入）：book_tags 依赖 books / tags 已先回填（外键 + 预过滤需查实际 id），
     * 因此要求它在文件顺序上晚于 books 与 tags；其余表顺序不限。
     *
     * @return null = 通过；否则为 [BackupException]（界面层映射中文/英文文案）
     */
    fun validateTableOrder(tableKeys: List<String>): BackupException? {
        val bookTags = tableKeys.indexOf("book_tags")
        if (bookTags < 0) return null
        if (tableKeys.indexOf("books") > bookTags || tableKeys.indexOf("tags") > bookTags) {
            return BackupException(BackupError.TABLE_ORDER)
        }
        return null
    }

    /**
     * V1.3 收尾：导出侧与导入侧共用同一上限，避免「导出成功但自己无法恢复」的备份。
     * false 表示备份超出当前版本支持的大小（调用方给中文提示）。
     */
    fun bytesWithinLimit(byteCount: Long): Boolean = byteCount in 0..MAX_FILE_BYTES

    /** 各表主键列（P2-3：检测备份内重复主键，后者覆盖前者），与 Room 实体保持一致 */
    val PRIMARY_KEYS: Map<String, List<String>> = linkedMapOf(
        "books" to listOf("id"),
        "chapters" to listOf("id"),
        "reading_progress" to listOf("book_id"),
        "bookmarks" to listOf("id"),
        "tags" to listOf("id"),
        "book_tags" to listOf("book_id", "tag_id"),
        "scan_folders" to listOf("id"),
    )

    /** 核心表（V1.4）：缺失即视为损坏备份，拒绝导入 */
    val CORE_TABLES = listOf("books", "chapters")

    /** 参与备份的表；tags / book_tags 可能不存在（v1 库），导出与导入都按表存在性跳过 */
    val TABLES = listOf(
        "books",
        "chapters",
        "reading_progress",
        "bookmarks",
        "tags",
        "book_tags",
        "scan_folders",
    )

    /** 覆盖导入前的清空顺序：子表 → 父表（外键安全） */
    val CLEAR_ORDER = listOf(
        "book_tags",
        "tags",
        "bookmarks",
        "reading_progress",
        "chapters",
        "books",
        "scan_folders",
    )

    /** 回填顺序：父表 → 子表；显式保留原 id，保证进度 / 书签 / 标签映射正确 */
    val INSERT_ORDER = listOf(
        "books",
        "chapters",
        "reading_progress",
        "bookmarks",
        "tags",
        "book_tags",
        "scan_folders",
    )

    /** 顶层元信息字段（不参与表解析） */
    private val META_KEYS = setOf("schema", "app", "versionName", "exportedAt", "settings")

    enum class SchemaCheck { OK, TOO_NEW, INVALID }

    /** schema 版本校验：null/非 1 → 无效；>1 → 来自更高版本 */
    fun checkSchema(version: Int?): SchemaCheck = when {
        version == null -> SchemaCheck.INVALID
        version > SCHEMA_VERSION -> SchemaCheck.TOO_NEW
        version == SCHEMA_VERSION -> SchemaCheck.OK
        else -> SchemaCheck.INVALID
    }

    /** 默认备份文件名：yyx-backup-yyyyMMdd-HHmm.json */
    fun defaultFileName(epochMs: Long = System.currentTimeMillis()): String =
        FILE_NAME_PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(epochMs)) +
            FILE_NAME_SUFFIX

    /**
     * 备份中的设置快照；除 [ttsEngine] 外的字段为 null 表示备份未包含该项（导入时保留当前值）。
     */
    data class BackupSettings(
        val fontSizeSp: Int? = null,
        val lineSpacingDp: Int? = null,
        val theme: String? = null,
        val ttsRate: Float? = null,
        /** 用户指定 TTS 引擎包名；null = 自动选择引擎 */
        val ttsEngine: String? = null,
        /**
         * 备份中是否显式包含 ttsEngine 字段。
         * 用于区分「文件里写了 null（恢复为自动）」与「旧文件没有该字段（保留当前值）」。
         */
        val ttsEngineSpecified: Boolean = false,
        /** 听书强制外放（V1.3 新增）；null = 备份未包含该项 */
        val ttsForceSpeaker: Boolean? = null,
        /** 备份中是否显式包含 ttsForceSpeaker 字段（区分「写了 false」与「旧文件没有该字段」） */
        val ttsForceSpeakerSpecified: Boolean = false,
        /** 首选输出设备选择器（V2 精确音频路由）；null = 备份未包含该项。地址跨设备不可迁移，恢复后匹配不到自动回退跟随系统 */
        val ttsPreferredOutput: String? = null,
        /** 备份中是否显式包含 ttsPreferredOutput 字段（区分「写了 null」与「旧文件没有该字段」） */
        val ttsPreferredOutputSpecified: Boolean = false,
    ) {
        /** 合并到阅读设置：缺项保留当前值，越界值收敛到既有合法区间 */
        fun toReaderSettings(base: ReaderSettings): ReaderSettings = base.copy(
            fontSizeSp = fontSizeSp?.coerceIn(12, 32) ?: base.fontSizeSp,
            lineSpacingDp = lineSpacingDp?.coerceIn(0, 20) ?: base.lineSpacingDp,
            theme = theme ?: base.theme,
        )

        /** 合并到听书设置：缺项保留当前值；显式 null 的 ttsEngine 表示恢复为「自动」 */
        fun toListenSettings(base: ListenSettings): ListenSettings = base.copy(
            rate = ttsRate?.coerceIn(0.5f, 3.0f) ?: base.rate,
            enginePackage = if (ttsEngineSpecified) ttsEngine else base.enginePackage,
            forceSpeaker = if (ttsForceSpeakerSpecified) ttsForceSpeaker ?: base.forceSpeaker else base.forceSpeaker,
            preferredOutput = if (ttsPreferredOutputSpecified) ttsPreferredOutput else base.preferredOutput,
        )
    }

    /** 备份内容：设置 + 各表行 */
    data class BackupContent(
        val settings: BackupSettings?,
        val tables: Map<String, List<BackupRow>>,
    )

    /** 解码结果：元信息 + 内容 */
    data class BackupDocument(
        val meta: BackupMeta,
        val content: BackupContent,
    )

    /** 备份文件顶层元信息（解码用） */
    data class BackupMeta(
        val schemaVersion: Int?,
        val appId: String?,
        val versionName: String?,
        val exportedAt: Long,
    )

    /** 空备份判定：既无设置又无任何行 → 拒绝导入，避免误清空 */
    fun isEffectivelyEmpty(content: BackupContent): Boolean =
        content.settings == null && content.tables.values.all { it.isEmpty() }

    /** 单行列过滤结果：可用列 + 被跳过的列数 */
    data class ColumnSelection(val columns: List<String>, val skipped: Int)

    /** 从备份行挑出目标表实际存在的列，未知列丢弃（兼容旧 / 新 schema） */
    fun usableColumns(row: BackupRow, existingColumns: Set<String>): ColumnSelection {
        val usable = row.keys.filter { it in existingColumns }
        return ColumnSelection(usable, row.size - usable.size)
    }

    /** 导入计划：已按目标表存在性过滤的行 */
    data class ImportPlan(
        /** 表 → 行（列已过滤，顺序与备份一致）；**不含备份中缺键的表** */
        val rowsByTable: Map<String, List<BackupRow>>,
        /** 目标库不存在的表（v1 库无 tags / book_tags） */
        val skippedTables: List<String>,
        /**
         * 备份文件中**没有该表键**的表（P2-2）：不清空、不插入，保留本机现有数据。
         * 与「有键但为空数组」区分：后者按覆盖语义清空该表。
         */
        val missingTables: List<String>,
        /** 备份中出现但本版本不认识的表 */
        val ignoredTables: List<String>,
        /** 被跳过的未知列总数 */
        val skippedColumns: Int,
    )

    /**
     * 生成导入计划（纯函数）：
     * - 只处理 [TABLES] 中的表；目标库不存在的表记入 skippedTables；
     * - 备份中**缺表键**的表记入 missingTables（不清空、不插入，保留本机数据，P2-2）；
     * - 有键（哪怕空数组）的表进入 rowsByTable，按覆盖语义清空并回填；
     * - 逐行挑出目标表存在的列，未知列计入 skippedColumns；
     * - 备份中未知的表（未来 schema）记入 ignoredTables。
     */
    fun planImport(
        content: BackupContent,
        existingColumns: Map<String, Set<String>>,
    ): ImportPlan {
        val rowsByTable = LinkedHashMap<String, List<BackupRow>>()
        val skippedTables = mutableListOf<String>()
        val missingTables = mutableListOf<String>()
        var skippedColumns = 0

        INSERT_ORDER.forEach { table ->
            val columns = existingColumns[table]
            if (columns == null) {
                skippedTables += table
                return@forEach
            }
            val rawRows = content.tables[table]
            if (rawRows == null) {
                // P2-2：备份没有该表键 → 保留本机现有数据，不清空、不插入
                missingTables += table
                return@forEach
            }
            rowsByTable[table] = rawRows.map { row ->
                val selection = usableColumns(row, columns)
                skippedColumns += selection.skipped
                LinkedHashMap<String, Any?>().apply {
                    selection.columns.forEach { put(it, row[it]) }
                }
            }
        }

        val ignoredTables = content.tables.keys.filter { it !in TABLES && it !in META_KEYS }
        return ImportPlan(rowsByTable, skippedTables, missingTables, ignoredTables, skippedColumns)
    }

    /** 重复主键报告：duplicateRows = 被后者覆盖的行数（行数 - 不同主键数） */
    data class DuplicateReport(
        val duplicateRows: Int,
        val byTable: Map<String, Int>,
    )

    /**
     * P2-3：检测备份内容中同表重复主键（按 [PRIMARY_KEYS]）。
     * 缺主键列的行无法判定，直接跳过不计。纯函数，供单测覆盖。
     */
    fun countDuplicatePrimaryKeys(rowsByTable: Map<String, List<BackupRow>>): DuplicateReport {
        val byTable = LinkedHashMap<String, Int>()
        var total = 0
        PRIMARY_KEYS.forEach tableLoop@{ (table, keys) ->
            val rows = rowsByTable[table] ?: return@tableLoop
            if (rows.isEmpty()) return@tableLoop
            val seen = HashSet<String>(rows.size)
            var duplicates = 0
            rows.forEach rowLoop@{ row ->
                if (keys.any { !row.containsKey(it) }) return@rowLoop
                val key = keys.joinToString("\u0000") { column ->
                    row[column]?.let { "${it.javaClass.simpleName}:$it" } ?: "null"
                }
                if (!seen.add(key)) duplicates++
            }
            if (duplicates > 0) {
                byTable[table] = duplicates
                total += duplicates
            }
        }
        return DuplicateReport(total, byTable)
    }

    /** book_tags 过滤结果：有效行 + 被跳过的无效关联行数 */
    data class BookTagFilter(
        val validRows: List<BackupRow>,
        val skippedRows: Int,
    )

    /**
     * P2-4：过滤 book_tags 中引用了不存在书籍 / 标签的行。
     * book_tags 是 schema 中唯一带 DB 外键的表，孤儿关联会让整次恢复因约束失败；
     * 按 P2-4「告警、不自动删业务数据」的语义，这里只丢弃备份中本身不可用的关联行并计数。
     *
     * @param rows 备份中的 book_tags 行（列已过滤）
     * @param bookIds 本次导入后 books 的有效 id 集合（备份缺 books 键时传本机现有 id）
     * @param tagIds 本次导入后 tags 的有效 id 集合（备份缺 tags 键时传本机现有 id）
     */
    fun filterValidBookTags(
        rows: List<BackupRow>,
        bookIds: Set<Long>,
        tagIds: Set<Long>,
    ): BookTagFilter {
        if (rows.isEmpty()) return BookTagFilter(rows, 0)
        val valid = rows.filter { row ->
            val bookId = (row["book_id"] as? Number)?.toLong()
            val tagId = (row["tag_id"] as? Number)?.toLong()
            bookId != null && tagId != null && bookId in bookIds && tagId in tagIds
        }
        return BookTagFilter(valid, rows.size - valid.size)
    }

    /** 备份文件中出现的顶层数据表名（不含元信息字段），供解码时识别未知表 */
    fun isMetaKey(key: String): Boolean = key in META_KEYS
}
