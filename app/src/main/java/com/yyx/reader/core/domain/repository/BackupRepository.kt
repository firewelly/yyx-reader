package com.yyx.reader.core.domain.repository

import android.net.Uri
import com.yyx.reader.core.domain.model.BackupResult

/**
 * 备份与恢复（PRD F-12）：SAF 单文件 JSON，纯本地、无网络。
 *
 * 覆盖范围 = 数据库全部业务表 + 阅读 / 听书设置。
 * 恢复为**覆盖式**：清空当前全部数据后按原 id 回填，失败整体回滚。
 *
 * V1.3 健壮性语义：
 * - 备份中**缺某表键**时保留本机现有该表数据（只有「有键（含空数组）」才覆盖清空）；
 * - 统计为回填后的真实行数；重复主键、引用完整性、书源不可访问等只告警不删数据；
 * - 备份文件上限 16MB；book_tags 中引用不存在书籍/标签的关联行会被跳过并计数。
 */
interface BackupRepository {

    /** 导出全量备份到 uri；返回统计或中文失败消息 */
    suspend fun export(uri: Uri): BackupResult

    /** 从 uri 覆盖恢复；返回统计（含真实行数、跳过的表 / 列 / 无效关联与中文告警）或中文失败消息 */
    suspend fun import(uri: Uri): BackupResult
}
