package com.yyx.reader.core.domain.model

/**
 * 备份 / 恢复的统计摘要（PRD F-12）。
 *
 * 导出时表示写入文件的记录；导入时表示本次实际恢复的记录。
 */
data class BackupSummary(
    /** 备份文件字节数 */
    val byteCount: Long = 0,
    /** 备份生成时间（导入时为文件内记录的导出时间） */
    val exportedAt: Long = 0,
    /** 是否包含设置，且（导入时）设置已成功应用 */
    val settingsIncluded: Boolean = false,
    /** 各表记录数：导出=导出条数；导入=写入条数 */
    val tableCounts: Map<String, Int> = emptyMap(),
    /** 目标库不存在而被跳过的表（v1 库无 tags / book_tags 时） */
    val skippedTables: List<String> = emptyList(),
    /** 目标表不存在该列而被跳过的列数（兼容旧 / 新 schema） */
    val skippedColumns: Int = 0,
    /** 部分成功提示（如设置恢复失败），中文，直接展示给用户 */
    val warnings: List<String> = emptyList(),
) {
    val totalRows: Int get() = tableCounts.values.sum()

    /** 单行中文摘要，供 Snackbar / Toast 展示 */
}

/** 备份操作结果：失败统一返回中文消息，不抛异常给 UI */
sealed interface BackupResult {
    data class Success(val summary: BackupSummary) : BackupResult
    data class Failure(val message: String) : BackupResult
}
