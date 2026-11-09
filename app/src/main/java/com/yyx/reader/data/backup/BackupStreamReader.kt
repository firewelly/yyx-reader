package com.yyx.reader.data.backup

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 备份 JSON **流式读取器**（V1.4，PRD F-12）：
 * 用 Android 自带的 [JsonReader] 解析，SAF 输入流不可 seek，因此分两遍：
 * 1. [scan]：只读元信息（schema / exportedAt / settings）+ 顶层表键 + 总字节数，**不把任何表行读进内存**；
 * 2. [streamRows]：第二遍逐表逐行回调，行级内存有界。
 *
 * 只用 Android 平台 API，不引入任何第三方 JSON 库。
 */
object BackupStreamReader {

    /** 第一遍扫描结果 */
    data class Scan(
        val schemaVersion: Int?,
        val exportedAt: Long,
        /** 备份文件中的设置（可能为 null = 旧备份不含设置） */
        val settings: BackupFormat.BackupSettings?,
        /** 文件中出现的顶层表键（按文件顺序） */
        val tableKeys: List<String>,
        /** 各表行数（第一遍顺带统计，用于「空备份」判定与提示；不缓存行内容） */
        val rowCounts: Map<String, Int>,
        /** 文件总字节数（已校验不超过上限） */
        val byteCount: Long,
    )

    /**
     * 第一遍：扫描元信息与表键。
     *
     * @param open 打开输入流的工厂（每遍各调用一次，关闭由内部负责）
     * @param maxBytes 大小上限，超过抛中文异常「备份文件过大」
     */
    fun scan(open: () -> InputStream, maxBytes: Long): Scan {
        val stream = CountingInputStream(open() ?: throw BackupFormat.BackupException(BackupFormat.BackupError.READ_FAILED))
        var schemaVersion: Int? = null
        var exportedAt = 0L
        var settings: BackupFormat.BackupSettings? = null
        val tableKeys = mutableListOf<String>()
        val rowCounts = LinkedHashMap<String, Int>()

        stream.use { input ->
            val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = false }
            try {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    throw BackupFormat.BackupException(BackupFormat.BackupError.NOT_JSON)
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    when (name) {
                        "schema" -> schemaVersion = if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull(); null
                        } else {
                            reader.nextInt()
                        }
                        "exportedAt" -> exportedAt = if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull(); 0L
                        } else {
                            reader.nextLong()
                        }
                        "settings" -> settings = if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull(); null
                        } else {
                            readSettings(reader)
                        }
                        "app", "versionName" -> reader.skipValue()
                        else -> {
                            // 数据表键（含未来版本未知表，交由导入计划忽略并计数）
                            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                tableKeys += name
                                var rows = 0
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    reader.skipValue()
                                    rows++
                                    ensureWithinLimit(input, maxBytes)
                                }
                                reader.endArray()
                                rowCounts[name] = rows
                            } else {
                                reader.skipValue()
                            }
                        }
                    }
                    ensureWithinLimit(input, maxBytes)
                }
                reader.endObject()
            } catch (e: BackupFormat.SizeLimitException) {
                throw e
            } catch (e: BackupFormat.BackupException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw BackupFormat.BackupException(BackupFormat.BackupError.NOT_JSON)
            } catch (e: IllegalStateException) {
                throw BackupFormat.BackupException(BackupFormat.BackupError.NOT_JSON)
            } catch (e: java.io.IOException) {
                throw BackupFormat.BackupException(BackupFormat.BackupError.NOT_JSON)
            }
        }
        ensureWithinLimit(stream, maxBytes, force = true)
        return Scan(schemaVersion, exportedAt, settings, tableKeys, rowCounts, stream.count)
    }

    /**
     * 第二遍：逐表逐行回调。
     *
     * @param onTableStart 进入某表数组前回调（用于清空/准备）
     * @param onRow 每行回调；row 为列名 → 标量值（Long / Double / String / Boolean / null）
     */
    fun streamRows(
        open: () -> InputStream,
        onTableStart: (String) -> Unit,
        onRow: (String, BackupRow) -> Unit,
    ) {
        open()?.use { input ->
            val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = false }
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in BackupFormat.TABLES) {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                        throw BackupFormat.BackupException(BackupFormat.BackupError.TABLE_NOT_ARRAY, name)
                    }
                    onTableStart(name)
                    reader.beginArray()
                    while (reader.hasNext()) {
                        onRow(name, readRow(reader))
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        } ?: throw BackupFormat.BackupException(BackupFormat.BackupError.READ_FAILED)
    }

    // ---------- 内部解析 ----------

    private fun readSettings(reader: JsonReader): BackupFormat.BackupSettings {
        var fontSizeSp: Int? = null
        var lineSpacingDp: Int? = null
        var theme: String? = null
        var ttsRate: Float? = null
        var ttsEngine: String? = null
        var ttsEngineSpecified = false
        var ttsForceSpeaker: Boolean? = null
        var ttsForceSpeakerSpecified = false
        var ttsPreferredOutput: String? = null
        var ttsPreferredOutputSpecified = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "fontSizeSp" -> fontSizeSp = optInt(reader)
                "lineSpacingDp" -> lineSpacingDp = optInt(reader)
                "theme" -> theme = optString(reader)
                "ttsRate" -> ttsRate = optDouble(reader)?.toFloat()
                "ttsEngine" -> {
                    ttsEngineSpecified = true
                    ttsEngine = optString(reader)
                }
                "ttsForceSpeaker" -> {
                    ttsForceSpeakerSpecified = true
                    ttsForceSpeaker = optBoolean(reader)
                }
                "ttsPreferredOutput" -> {
                    ttsPreferredOutputSpecified = true
                    ttsPreferredOutput = optString(reader)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return BackupFormat.BackupSettings(
            fontSizeSp = fontSizeSp,
            lineSpacingDp = lineSpacingDp,
            theme = theme,
            ttsRate = ttsRate,
            ttsEngine = ttsEngine,
            ttsEngineSpecified = ttsEngineSpecified,
            ttsForceSpeaker = ttsForceSpeaker,
            ttsForceSpeakerSpecified = ttsForceSpeakerSpecified,
            ttsPreferredOutput = ttsPreferredOutput,
            ttsPreferredOutputSpecified = ttsPreferredOutputSpecified,
        )
    }

    private fun readRow(reader: JsonReader): BackupRow {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw BackupFormat.BackupException(BackupFormat.BackupError.ROW_NOT_OBJECT)
        }
        val row = LinkedHashMap<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val column = reader.nextName()
            row[column] = when (reader.peek()) {
                JsonToken.NULL -> { reader.nextNull(); null }
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextString().let { raw ->
                    // 整数 → Long（保持 id / 时间戳精度），小数 → Double
                    raw.toLongOrNull() ?: raw.toDouble()
                }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                else -> { reader.skipValue(); null }
            }
        }
        reader.endObject()
        return row
    }

    private fun optInt(reader: JsonReader): Int? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.NUMBER -> reader.nextInt()
        else -> { reader.skipValue(); null }
    }

    private fun optDouble(reader: JsonReader): Double? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.NUMBER -> reader.nextDouble()
        else -> { reader.skipValue(); null }
    }

    private fun optString(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.STRING -> reader.nextString()
        else -> { reader.skipValue(); null }
    }

    private fun optBoolean(reader: JsonReader): Boolean? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        else -> { reader.skipValue(); null }
    }

    private fun ensureWithinLimit(input: CountingInputStream, maxBytes: Long) {
        if (input.count > maxBytes) throw BackupFormat.SizeLimitException(maxBytes)
    }

    private fun ensureWithinLimit(input: CountingInputStream, maxBytes: Long, force: Boolean) {
        if (force && input.count > maxBytes) throw BackupFormat.SizeLimitException(maxBytes)
    }

    /** 计数输入流：统计已读字节，用于流式大小上限（不缓存内容） */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun close() = delegate.close()
    }
}
