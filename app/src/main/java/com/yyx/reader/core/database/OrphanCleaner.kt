package com.yyx.reader.core.database

import android.util.Log
import androidx.room.withTransaction
import com.yyx.reader.core.database.dao.BookmarkDao
import com.yyx.reader.core.database.dao.ChapterDao
import com.yyx.reader.core.database.dao.ProgressDao
import com.yyx.reader.core.database.dao.TagDao
import javax.inject.Inject
import javax.inject.Singleton

/** 孤儿行清理结果（各类实际删除行数） */
data class CleanupReport(
    val chapters: Int = 0,
    val progress: Int = 0,
    val bookmarks: Int = 0,
    val bookTags: Int = 0,
) {
    val total: Int get() = chapters + progress + bookmarks + bookTags
}

/**
 * 历史孤儿行清理（V1.4）：V1.3 之前 [com.yyx.reader.data.repository.BookRepositoryImpl.deleteBook]
 * 只删 books 行，chapters / reading_progress / bookmarks 没有数据库外键，于是留下 book_id 悬空的孤儿行；
 * book_tags 在外部工具/外键未开启时也可能有任一端失效的关联。
 *
 * 启动时由 [com.yyx.reader.YYXApp] 在 IO 线程执行一次（失败只记日志，不影响启动）：
 * 全部删除在**单个 Room 事务**内完成，返回 [CleanupReport] 便于日志/测试断言。
 *
 * 注意：这是「启动时维护」，与备份导入「只告警不删数据」的语义刻意分开——导入流程绝不自动删用户数据。
 */
@Singleton
class OrphanCleaner @Inject constructor(
    private val database: YYXDatabase,
    private val chapterDao: ChapterDao,
    private val progressDao: ProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val tagDao: TagDao,
) {

    /** 清理所有孤儿行；幂等（无孤儿时返回全 0 的 [CleanupReport]） */
    suspend fun cleanup(): CleanupReport {
        val report = database.withTransaction {
            CleanupReport(
                chapters = chapterDao.deleteOrphans(),
                progress = progressDao.deleteOrphans(),
                bookmarks = bookmarkDao.deleteOrphans(),
                bookTags = tagDao.deleteOrphanLinks(),
            )
        }
        Log.i(
            TAG,
            "孤儿行清理完成：chapters=${report.chapters} · progress=${report.progress} · " +
                "bookmarks=${report.bookmarks} · bookTags=${report.bookTags}（共 ${report.total} 行）",
        )
        return report
    }

    private companion object {
        const val TAG = "YYXOrphanCleaner"
    }
}
