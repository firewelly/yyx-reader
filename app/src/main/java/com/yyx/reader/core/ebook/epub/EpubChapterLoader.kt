package com.yyx.reader.core.ebook.epub

import com.yyx.reader.R
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 单个 zip 条目超过上限（纯 JVM 层技术异常，消息语言中性；界面层按需兜底映射） */
class ZipEntryTooLargeException(val maxBytes: Long) : IllegalStateException("ZIP entry exceeds $maxBytes bytes")

/** zip 条目读取工具（纯 JVM，扫描与加载共用） */
object ZipScanner {

    /** 在 zip 流中查找候选条目并读取全部字节 */
    fun readEntry(stream: InputStream, entryPath: String): ByteArray? {
        val candidates = EpubParser.entryCandidates(entryPath).toHashSet()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name in candidates) {
                    return zip.readBounded(MAX_ENTRY_BYTES)
                }
                zip.closeEntry()
            }
        }
    }

    /** 单次遍历收集所有条目名 + 小条目内容（用于解析 OPF/容器 XML） */
    fun snapshot(stream: InputStream, maxSmallBytes: Long = 2L * 1024 * 1024): Snapshot {
        val names = mutableListOf<String>()
        val blobs = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                val size = entry.size
                if (size in 1..maxSmallBytes) {
                    blobs[entry.name] = zip.readBounded(maxSmallBytes)
                }
                zip.closeEntry()
            }
        }
        return Snapshot(names, blobs)
    }

    data class Snapshot(val names: List<String>, val smallBlobs: Map<String, ByteArray>)

    private fun ZipInputStream.readBounded(max: Long): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw ZipEntryTooLargeException(max)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
}

/**
 * EPUB 章节加载：首次解析抽取纯文本并落盘缓存，之后直接读缓存（架构文档 §5.3）。
 */
@Singleton
class EpubChapterLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 加载章节纯文本；缓存未命中时打开 zip 流式查找条目 */
    fun load(fileUri: String, bookId: Long, chapterIdx: Int, epubHref: String): String {
        val cacheFile = cacheFile(bookId, chapterIdx)
        if (cacheFile.isFile) {
            return try {
                cacheFile.readText()
            } catch (_: Exception) {
                cacheFile.delete()
                ""
            }
        }
        val htmlBytes = openZip(fileUri)?.let { ZipScanner.readEntry(it, epubHref) } ?: return ""
        val text = HtmlTextExtractor.toPlainText(String(htmlBytes, Charsets.UTF_8))
        if (text.isNotEmpty()) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(text)
            }
        }
        return text
    }

    /** 提取封面到 covers/{bookId}.jpg，返回绝对路径；无封面返回 null */
    fun extractCover(fileUri: String, bookId: Long, coverHref: String?): String? {
        if (coverHref.isNullOrBlank()) return null
        val bytes = openZip(fileUri)?.let { ZipScanner.readEntry(it, coverHref) } ?: return null
        return try {
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val out = File(dir, "$bookId.jpg")
            out.writeBytes(bytes)
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun deleteCacheForBook(bookId: Long) {
        File(context.filesDir, "epub_text/$bookId").deleteRecursively()
    }

    private fun cacheFile(bookId: Long, idx: Int): File =
        File(File(context.filesDir, "epub_text/$bookId"), "$idx.txt")

    private fun openZip(fileUri: String): InputStream? =
        context.contentResolver.openInputStream(Uri.parse(fileUri))
}
