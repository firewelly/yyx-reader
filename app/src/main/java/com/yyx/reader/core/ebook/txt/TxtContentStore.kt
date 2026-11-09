package com.yyx.reader.core.ebook.txt

import com.yyx.reader.R
import android.content.Context
import android.net.Uri
import java.nio.charset.Charset

/**
 * TXT 章节内容加载：单书全量字节缓存 + 按字节偏移切片解码（架构文档 §5.2）。
 * 单槽缓存：同一时间只读一本书，避免多本大文件同时驻留内存。
 */
object TxtContentStore {

    private const val MAX_FILE_BYTES = 128L * 1024 * 1024

    private var cachedKey: String? = null
    private var cachedBytes: ByteArray? = null

    @Synchronized
    fun loadSlice(
        context: Context,
        fileUri: String,
        charsetName: String,
        startByte: Long,
        endByte: Long,
    ): String {
        val bytes = ensureBytes(context, fileUri)
        val s = startByte.coerceIn(0, bytes.size.toLong()).toInt()
        val e = endByte.coerceIn(0, bytes.size.toLong()).toInt()
        if (e <= s) return ""
        val text = String(bytes, s, e - s, charsetFor(charsetName))
        return if (startByte <= 0) text.trimStart('\uFEFF') else text
    }

    fun charsetFor(name: String): Charset = EncodingDetector.charsetFor(name)

    fun dropCache() {
        synchronized(this) {
            cachedKey = null
            cachedBytes = null
        }
    }

    private fun ensureBytes(context: Context, fileUri: String): ByteArray {
        cachedBytes?.let { bytes ->
            if (cachedKey == fileUri) return bytes
        }
        val bytes = readAll(context, fileUri)
        cachedKey = fileUri
        cachedBytes = bytes
        return bytes
    }

    /** SAF 读取全文（扫描与缓存共用），超限抛 IllegalStateException */
    fun readAll(context: Context, fileUri: String, maxBytes: Long = MAX_FILE_BYTES): ByteArray {
        val stream = context.contentResolver.openInputStream(Uri.parse(fileUri))
            ?: throw IllegalStateException(context.getString(R.string.txt_open_failed, fileUri))
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(input.available().toLong(), maxBytes).toInt().coerceAtLeast(8192))
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IllegalStateException(context.getString(R.string.txt_too_large, maxBytes / 1024 / 1024))
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
