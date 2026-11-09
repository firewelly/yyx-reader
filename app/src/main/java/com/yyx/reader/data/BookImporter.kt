package com.yyx.reader.data

import com.yyx.reader.R
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.dao.BookDao
import com.yyx.reader.core.domain.model.BookFormat
import com.yyx.reader.core.domain.model.Chapter
import com.yyx.reader.core.ebook.epub.EpubChapterLoader
import com.yyx.reader.core.ebook.epub.EpubParser
import com.yyx.reader.core.ebook.epub.EpubToc
import com.yyx.reader.core.ebook.epub.HtmlTextExtractor
import com.yyx.reader.core.ebook.epub.ZipScanner
import com.yyx.reader.core.ebook.txt.EncodingDetector
import com.yyx.reader.core.ebook.txt.TxtChapterIndexer
import com.yyx.reader.core.ebook.txt.TxtContentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单本书导入：解析 → 建章节索引 → 入库；重扫时增量更新并保留进度（PRD F-01）。
 */
@Singleton
class BookImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val db: YYXDatabase,
    private val epubLoader: EpubChapterLoader,
) {

    enum class Outcome { ADDED, UPDATED, SKIPPED, FAILED }

    data class Result(val outcome: Outcome, val message: String? = null)

    suspend fun importDocument(
        doc: DocumentFile,
        forcedCharset: String? = null,
        force: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val name = doc.name ?: return@withContext Result(Outcome.SKIPPED, context.getString(R.string.importer_no_filename))
            val lower = name.lowercase()
            val existing = bookDao.getByUri(doc.uri.toString())
            val mtime = doc.lastModified()
            val size = doc.length()

            // 增量：未变化直接跳过（PRD F-01-08）；force=true 用于「重新解析」
            if (!force &&
                existing != null &&
                existing.fileMtime == mtime &&
                existing.fileSize == size &&
                existing.chapterCount > 0
            ) {
                return@withContext Result(Outcome.SKIPPED)
            }

            val result = when {
                lower.endsWith(".txt") -> importTxt(name, doc, existing, forcedCharset, mtime, size)
                lower.endsWith(".epub") -> importEpub(name, doc, existing, mtime, size)
                else -> Result(Outcome.FAILED, context.getString(R.string.importer_unsupported, name))
            }
            result
        } catch (e: Exception) {
            Result(Outcome.FAILED, "${doc.name}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- TXT ----------

    private suspend fun importTxt(
        fileName: String,
        doc: DocumentFile,
        existing: com.yyx.reader.core.database.entity.BookEntity?,
        forcedCharset: String?,
        mtime: Long,
        size: Long,
    ): Result {
        val bytes = TxtContentStore.readAll(context, doc.uri.toString())
        if (bytes.isEmpty()) return Result(Outcome.FAILED, context.getString(R.string.importer_empty_file, fileName))

        val charset = forcedCharset
            ?: existing?.charset
            ?: EncodingDetector.detect(bytes)
        val indexed = TxtChapterIndexer.index(bytes, charset)
        if (indexed.isEmpty()) return Result(Outcome.FAILED, context.getString(R.string.importer_no_text, fileName))

        val title = fileName.removeSuffix(".txt").removeSuffix(".TXT").ifBlank { context.getString(R.string.importer_untitled) }
        val now = System.currentTimeMillis()

        val bookId = db.withTransaction {
            val id = existing?.id ?: bookDao.insert(
                com.yyx.reader.core.database.entity.BookEntity(
                    title = title,
                    format = BookFormat.TXT.name.lowercase(),
                    fileUri = doc.uri.toString(),
                    charset = charset,
                    chapterCount = indexed.size,
                    wordCount = indexed.sumOf { it.charCount.toLong() },
                    fileMtime = mtime,
                    fileSize = size,
                    addedAt = now,
                    updatedAt = now,
                ),
            ).takeIf { it != -1L } ?: bookDao.getByUri(doc.uri.toString())!!.id
            bookDao.update(
                (bookDao.getById(id)!!).copy(
                    title = title,
                    charset = charset,
                    chapterCount = indexed.size,
                    wordCount = indexed.sumOf { it.charCount.toLong() },
                    fileMtime = mtime,
                    fileSize = size,
                    updatedAt = now,
                ),
            )
            id
        }

        saveChapters(
            bookId,
            indexed.map {
                Chapter(
                    idx = it.idx,
                    title = it.title,
                    startOffset = it.startOffset,
                    endOffset = it.endOffset,
                    charCount = it.charCount,
                )
            },
        )
        return Result(if (existing == null) Outcome.ADDED else Outcome.UPDATED)
    }

    // ---------- EPUB ----------

    private suspend fun importEpub(
        fileName: String,
        doc: DocumentFile,
        existing: com.yyx.reader.core.database.entity.BookEntity?,
        mtime: Long,
        size: Long,
    ): Result {
        val fileUri = doc.uri.toString()
        val snapshot = context.contentResolver.openInputStream(Uri.parse(fileUri))
            ?.use { ZipScanner.snapshot(it) }
            ?: return Result(Outcome.FAILED, context.getString(R.string.importer_unopenable, fileName))

        val meta = EpubParser.parse(snapshot.names) { snapshot.smallBlobs[it] }
            ?: return Result(Outcome.FAILED, context.getString(R.string.importer_epub_parse, fileName))

        // NCX 目录标题（可选）
        val tocTitles = snapshot.names.firstOrNull { it.endsWith(".ncx", true) }
            ?.let { ncxName -> snapshot.smallBlobs[ncxName]?.let { EpubToc.parseTitles(it) } }
            ?: emptyMap()

        // 章节文本抽取（计算 charCount）
        val chapters = mutableListOf<Chapter>()
        meta.spineHrefs.forEachIndexed { k, href ->
            val htmlBytes = entryBytes(snapshot, href, fileUri) ?: return@forEachIndexed
            val text = HtmlTextExtractor.toPlainText(String(htmlBytes, Charsets.UTF_8))
            val fallbackTitle = context.getString(R.string.chapter_default_title, k + 1)
            val hrefBase = href.substringBeforeLast('/')
            val ncxTitle = tocTitles[href] ?: tocTitles.entries.firstOrNull {
                EpubParser.entryCandidates(it.key).contains(href)
            }?.value
            chapters.add(
                Chapter(
                    idx = chapters.size,
                    title = ncxTitle ?: fallbackTitle,
                    startOffset = -1,
                    endOffset = -1,
                    charCount = text.length,
                    epubHref = href,
                ),
            )
        }
        if (chapters.isEmpty()) return Result(Outcome.FAILED, context.getString(R.string.importer_no_chapters, fileName))

        val title = meta.title ?: fileName.removeSuffix(".epub").removeSuffix(".EPUB").ifBlank { context.getString(R.string.importer_untitled) }
        val now = System.currentTimeMillis()

        val bookId = db.withTransaction {
            val id = existing?.id ?: bookDao.insert(
                com.yyx.reader.core.database.entity.BookEntity(
                    title = title,
                    format = BookFormat.EPUB.name.lowercase(),
                    fileUri = fileUri,
                    chapterCount = chapters.size,
                    wordCount = chapters.sumOf { it.charCount.toLong() },
                    fileMtime = mtime,
                    fileSize = size,
                    addedAt = now,
                    updatedAt = now,
                ),
            ).takeIf { it != -1L } ?: bookDao.getByUri(fileUri)!!.id
            bookDao.update(
                (bookDao.getById(id)!!).copy(
                    title = title,
                    author = meta.author ?: existing?.author,
                    summary = meta.summary ?: existing?.summary,
                    chapterCount = chapters.size,
                    wordCount = chapters.sumOf { it.charCount.toLong() },
                    fileMtime = mtime,
                    fileSize = size,
                    updatedAt = now,
                ),
            )
            id
        }

        saveChapters(bookId, chapters)
        // 封面（bookId 确定后提取）
        if (existing == null || existing.coverPath == null) {
            val coverPath = epubLoader.extractCover(fileUri, bookId, meta.coverHref)
            if (coverPath != null) bookDao.updateCover(bookId, coverPath)
        }
        return Result(if (existing == null) Outcome.ADDED else Outcome.UPDATED)
    }

    private fun entryBytes(
        snapshot: ZipScanner.Snapshot,
        href: String,
        fileUri: String,
    ): ByteArray? {
        val candidates = EpubParser.entryCandidates(href).toHashSet()
        snapshot.names.firstOrNull { it in candidates }?.let { name ->
            snapshot.smallBlobs[name]?.let { return it }
        }
        return context.contentResolver.openInputStream(Uri.parse(fileUri))
            ?.let { ZipScanner.readEntry(it, href) }
    }

    private suspend fun saveChapters(bookId: Long, chapters: List<Chapter>) {
        db.withTransaction {
            db.chapterDao().deleteByBook(bookId)
            db.chapterDao().insertAll(
                chapters.map {
                    com.yyx.reader.core.database.entity.ChapterEntity(
                        bookId = bookId,
                        idx = it.idx,
                        title = it.title,
                        startOffset = it.startOffset,
                        endOffset = it.endOffset,
                        charCount = it.charCount,
                        epubHref = it.epubHref,
                    )
                },
            )
        }
    }
}
