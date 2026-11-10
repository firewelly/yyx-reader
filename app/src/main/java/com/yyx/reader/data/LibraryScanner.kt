package com.yyx.reader.data

import com.yyx.reader.R
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.yyx.reader.core.database.dao.ScanFolderDao
import com.yyx.reader.core.domain.model.ScanReport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 书库扫描器：遍历已授权的 SAF 文件夹 → 逐个导入/增量更新（架构文档 §5.1）。
 */
@Singleton
class LibraryScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanFolderDao: ScanFolderDao,
    private val importer: BookImporter,
) {

    suspend fun scan(
        onProgress: suspend (processed: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
    ): ScanReport = withContext(Dispatchers.IO) {
        val report = ScanReportBuilder()
        val folders = scanFolderDao.getAll()

        // 1. 收集候选文件（先遍历后导入，进度条准确）
        val candidates = mutableListOf<DocumentFile>()
        for (folder in folders) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
                if (tree != null) walk(tree, 0, candidates)
            } catch (e: Exception) {
                report.errors.add(context.getString(R.string.library_scan_walk_failed, folder.displayName, e.message.orEmpty()))
            }
        }
        // V1.5.x 扫描方案 C（混合）：用户开启「所有文件访问」后，额外自动遍历常见公共目录
        // （Download / Documents）。SAF 已授权文件夹照常扫描；两处可能重叠，导入按内容幂等去重。
        if (allFilesAccessGranted()) {
            for (root in commonRoots()) {
                walkFiles(root, 0, candidates)
            }
        }

        // 2. 逐个导入
        report.total = candidates.size
        candidates.forEachIndexed { i, doc ->
            ensureActive()
            onProgress(i, candidates.size, doc.name ?: "...")
            val r = importer.importDocument(doc)
            when (r.outcome) {
                BookImporter.Outcome.ADDED -> report.added++
                BookImporter.Outcome.UPDATED -> report.updated++
                BookImporter.Outcome.SKIPPED -> report.skipped++
                BookImporter.Outcome.FAILED -> {
                    report.failed++
                    r.message?.let { report.errors.add(it) }
                }
            }
        }

        // 3. 更新文件夹扫描时间
        val now = System.currentTimeMillis()
        folders.forEach { scanFolderDao.touch(it.id, now) }
        report.build()
    }

    private fun walk(dir: DocumentFile, depth: Int, out: MutableList<DocumentFile>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        try {
            dir.listFiles().forEach { f ->
                if (out.size >= MAX_FILES) return
                if (f.isDirectory) {
                    walk(f, depth + 1, out)
                } else {
                    val name = f.name?.lowercase() ?: return@forEach
                    if (name.endsWith(".txt") || name.endsWith(".epub")) out.add(f)
                }
            }
        } catch (e: SecurityException) {
            // 无权限子目录：跳过
        }
    }

    /**
     * 「所有文件访问」模式下：java.io.File 直扫常见公共目录（Download / Documents），
     * 与 SAF 遍历产出同一候选列表（DocumentFile.fromFile 包装，导入管线不变）。
     */
    private fun walkFiles(dir: File, depth: Int, out: MutableList<DocumentFile>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val entries = dir.listFiles() ?: return
        for (f in entries) {
            if (out.size >= MAX_FILES) return
            if (f.isDirectory) {
                walkFiles(f, depth + 1, out)
            } else {
                val name = f.name.lowercase()
                if (name.endsWith(".txt") || name.endsWith(".epub")) out.add(DocumentFile.fromFile(f))
            }
        }
    }

    /** 是否已获得「所有文件访问」（API 30+；旧版本恒为 false，走纯 SAF 路径） */
    fun allFilesAccessGranted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()

    /** 「所有文件访问」模式下自动遍历的常见公共目录根 */
    private fun commonRoots(): List<File> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
    )

    private class ScanReportBuilder {
        var total = 0
        var added = 0
        var updated = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        fun build(): ScanReport = ScanReport(total, added, updated, skipped, failed, errors.toList())
    }

    companion object {
        private const val MAX_DEPTH = 8
        private const val MAX_FILES = 20000
    }
}
