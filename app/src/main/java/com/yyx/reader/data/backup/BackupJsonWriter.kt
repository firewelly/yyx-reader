package com.yyx.reader.data.backup

import java.io.OutputStream

/**
 * 备份 JSON **流式写出器**（V1.4，PRD F-12）：逐表逐行写入 [OutputStream]，
 * 全程不构造完整 JSON 字符串 / ByteArray，因此大书库导出时内存有界。
 *
 * 只依赖 `java.io`（不触达 Android API / org.json），可在 JVM 单元测试直接覆盖。
 *
 * 输出结构与 V1.3 完全一致（schema=1，向后兼容）：
 * ```
 * {"schema":1,"app":...,"versionName":...,"exportedAt":...,
 *  "settings":{...},
 *  "books":[{...}],...,"scan_folders":[{...}]}
 * ```
 * 表顺序固定为 [BackupFormat.TABLES]（导入侧按顺序流式回填，顺序不合法会被拒绝）。
 */
object BackupJsonWriter {

    /** 超限中止时抛出（消息由调用方按语言转成用户提示；[maxBytes] 供文案参数） */
    class SizeLimitExceeded(val maxBytes: Long) : Exception("Backup size limit exceeded ($maxBytes bytes)")

    /** 写出结果：实际写入字节数 */
    data class Result(val byteCount: Long, val tableCounts: Map<String, Int>)

    /**
     * 流式写出备份。
     *
     * @param streamRows 逐行回调写出（内存有界）：对每个表调用一次，由调用方遍历游标并 `emit` 每行；
     *                   返回该表实际写出的行数（表不存在 → emit 0 行 → 写出空数组，与 V1.3 一致）
     * @param maxBytes 写入字节上限，超过立即抛 [SizeLimitExceeded]（不继续写）
     */
    fun write(
        out: OutputStream,
        exportedAt: Long,
        versionName: String,
        settings: BackupFormat.BackupSettings?,
        tables: List<String>,
        streamRows: (table: String, emit: (BackupRow) -> Unit) -> Unit,
        maxBytes: Long,
    ): Result {
        val counter = CountingOutputStream(out)
        val tableCounts = LinkedHashMap<String, Int>()
        try {
            counter.write("{\"schema\":".toByteArray(Charsets.UTF_8))
            counter.write(BackupFormat.SCHEMA_VERSION.toString().toByteArray(Charsets.UTF_8))
            counter.write(",\"app\":".toByteArray(Charsets.UTF_8))
            counter.write(quote(BackupFormat.APP_ID).toByteArray(Charsets.UTF_8))
            counter.write(",\"versionName\":".toByteArray(Charsets.UTF_8))
            counter.write(quote(versionName).toByteArray(Charsets.UTF_8))
            counter.write(",\"exportedAt\":".toByteArray(Charsets.UTF_8))
            counter.write(exportedAt.toString().toByteArray(Charsets.UTF_8))
            if (settings != null) {
                counter.write(",\"settings\":".toByteArray(Charsets.UTF_8))
                writeSettings(counter, settings)
            }
            tables.forEach { table ->
                counter.write(",".toByteArray(Charsets.UTF_8))
                counter.write(quote(table).toByteArray(Charsets.UTF_8))
                counter.write(":[".toByteArray(Charsets.UTF_8))
                var rows = 0
                streamRows(table) { row ->
                    if (rows > 0) counter.write(",".toByteArray(Charsets.UTF_8))
                    writeRow(counter, row)
                    rows++
                    // 每写一行检查一次：大表也不会累积到超限才发现
                    checkLimit(counter, maxBytes)
                }
                counter.write("]".toByteArray(Charsets.UTF_8))
                tableCounts[table] = rows
                checkLimit(counter, maxBytes)
            }
            counter.write("}".toByteArray(Charsets.UTF_8))
            checkLimit(counter, maxBytes)
            counter.flush()
        } catch (e: SizeLimitExceeded) {
            throw e
        }
        return Result(counter.count, tableCounts)
    }

    private fun checkLimit(counter: CountingOutputStream, maxBytes: Long) {
        if (counter.count > maxBytes) throw SizeLimitExceeded(maxBytes)
    }

    private fun writeSettings(out: OutputStream, settings: BackupFormat.BackupSettings) {
        out.write("{".toByteArray(Charsets.UTF_8))
        val entries = buildList {
            settings.fontSizeSp?.let { add("fontSizeSp" to it.toString()) }
            settings.lineSpacingDp?.let { add("lineSpacingDp" to it.toString()) }
            settings.theme?.let { add("theme" to quote(it)) }
            settings.ttsRate?.let { add("ttsRate" to it.toString()) }
            if (settings.ttsEngineSpecified) {
                add("ttsEngine" to (settings.ttsEngine?.let(::quote) ?: "null"))
            }
            if (settings.ttsForceSpeakerSpecified) {
                add("ttsForceSpeaker" to ((settings.ttsForceSpeaker ?: false).toString()))
            }
            if (settings.ttsPreferredOutputSpecified) {
                add("ttsPreferredOutput" to (settings.ttsPreferredOutput?.let(::quote) ?: "null"))
            }
        }
        entries.forEachIndexed { index, (key, value) ->
            if (index > 0) out.write(",".toByteArray(Charsets.UTF_8))
            out.write("${quote(key)}:$value".toByteArray(Charsets.UTF_8))
        }
        out.write("}".toByteArray(Charsets.UTF_8))
    }

    private fun writeRow(out: OutputStream, row: BackupRow) {
        out.write("{".toByteArray(Charsets.UTF_8))
        row.entries.forEachIndexed { index, (column, value) ->
            if (index > 0) out.write(",".toByteArray(Charsets.UTF_8))
            out.write(quote(column).toByteArray(Charsets.UTF_8))
            out.write(":".toByteArray(Charsets.UTF_8))
            out.write(scalar(value).toByteArray(Charsets.UTF_8))
        }
        out.write("}".toByteArray(Charsets.UTF_8))
    }

    /** 标量序列化：Long/Int → 数字，Double/Float → 数字，Boolean → true/false，其余 → 字符串，null → null */
    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Long, is Int, is Short, is Byte -> value.toString()
        is Double -> if (value.isNaN() || value.isInfinite()) quote(value.toString()) else value.toString()
        is Float -> scalar(value.toDouble())
        is Boolean -> value.toString()
        else -> quote(value.toString())
    }

    /** JSON 字符串转义（引号 / 反斜杠 / 控制字符），非 ASCII 原样输出（UTF-8） */
    fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** 计数输出流：统计写入字节，超过上限由调用方检查 */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }
}
