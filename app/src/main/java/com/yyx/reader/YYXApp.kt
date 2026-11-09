package com.yyx.reader

import android.app.Application
import android.util.Log
import com.yyx.reader.core.database.OrphanCleaner
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class YYXApp : Application() {

    /** 启动时数据库维护：清理历史孤儿行（V1.4） */
    @Inject
    lateinit var orphanCleaner: OrphanCleaner

    /** 应用级协程作用域：启动维护不阻塞主线程，单任务失败不影响其他任务 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        cleanupOrphansOnStartup()
    }

    /**
     * 启动时清理历史孤儿行（chapters / reading_progress / bookmarks 悬空 book_id，book_tags 任一端失效关联）。
     * 放 IO 线程 + runCatching：清理失败只记日志，绝不阻塞/影响启动。
     */
    private fun cleanupOrphansOnStartup() {
        appScope.launch {
            runCatching { orphanCleaner.cleanup() }
                .onFailure { Log.w("YYXApp", "启动孤儿行清理失败（不影响使用）", it) }
        }
    }

    /** 本地崩溃日志（PRD F-09-06）：仅写文件，不上传；设置页可导出 */
    private fun installCrashLogger() {        val delegate = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(filesDir, "logs").apply { mkdirs() }
                File(dir, "crash_${System.currentTimeMillis()}.log").writeText(
                    buildString {
                        appendLine("Thread: ${thread.name}")
                        appendLine("Time: ${System.currentTimeMillis()}")
                        appendLine(Log.getStackTraceString(throwable))
                    },
                )
                // 最多保留 5 份（防止反复崩溃时日志占满存储）
                dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_CRASH_LOGS)?.forEach { it.delete() }
            }
            delegate?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        /** 崩溃日志保留份数上限（防存储溢出；超出删最旧） */
        const val MAX_CRASH_LOGS = 5
    }
}
