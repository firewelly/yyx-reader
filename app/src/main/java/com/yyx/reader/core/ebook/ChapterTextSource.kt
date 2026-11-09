package com.yyx.reader.core.ebook

import com.yyx.reader.R
import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.BookFormat
import com.yyx.reader.core.domain.model.Chapter
import com.yyx.reader.core.ebook.epub.EpubChapterLoader
import com.yyx.reader.core.ebook.txt.TxtContentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 章节纯文本供给：阅读器 / 听书 / 缓存共用的唯一文本入口（架构文档 §5）。
 */
@Singleton
class ChapterTextSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val epubLoader: EpubChapterLoader,
) {

    suspend fun load(book: Book, chapter: Chapter): String = withContext(Dispatchers.IO) {
        try {
            when (book.format) {
                BookFormat.TXT -> TxtContentStore.loadSlice(
                    context,
                    book.fileUri,
                    book.charset ?: "UTF-8",
                    chapter.startOffset,
                    chapter.endOffset,
                )
                BookFormat.EPUB -> epubLoader.load(
                    book.fileUri,
                    book.id,
                    chapter.idx,
                    chapter.epubHref ?: "",
                )
            }
        } catch (e: Exception) {
            // 加载失败兜底：不闪退，展示中文错误占位（PRD F-04-10）。
            // 不把 Java 原始英文异常抛给用户（DEF-2：备份恢复后源文件/SAF 授权失效时曾直出
            // "open failed: ENOENT" / "Permission Denial"），原始异常只进本机日志。
            android.util.Log.w("YYXChapterText", "章节加载失败 book=${book.id} ch=${chapter.idx}", e)
            val reason = context.getString(
                when (e) {
                    is SecurityException -> R.string.chapter_fail_no_permission
                    is java.io.FileNotFoundException -> R.string.chapter_fail_not_found
                    is java.io.IOException -> R.string.chapter_fail_read
                    else -> R.string.chapter_fail_parse
                },
            )
            context.getString(R.string.reader_chapter_load_failed_title) + "\n\n" +
                context.getString(R.string.chapter_load_failed_reason, reason) + "\n\n" +
                context.getString(R.string.chapter_load_failed_retry)
        }
    }
}
