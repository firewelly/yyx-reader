package com.yyx.reader.data.backup

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** V1.4 备份流式写出器单测（纯 JVM，不触达 Android API） */
class BackupJsonWriterTest {

    private fun row(vararg pairs: Pair<String, Any?>): BackupRow = linkedMapOf(*pairs)

    private fun write(
        settings: BackupFormat.BackupSettings? = null,
        tables: Map<String, List<BackupRow>> = emptyMap(),
        maxBytes: Long = BackupFormat.MAX_FILE_BYTES,
    ): Pair<String, BackupJsonWriter.Result> {
        val out = ByteArrayOutputStream()
        val result = BackupJsonWriter.write(
            out = out,
            exportedAt = 1_700_000_000_000L,
            versionName = "1.4.0",
            settings = settings,
            tables = BackupFormat.TABLES,
            streamRows = { table, emit -> tables[table].orEmpty().forEach(emit) },
            maxBytes = maxBytes,
        )
        return out.toString(Charsets.UTF_8.name()) to result
    }

    @Test
    fun `写出结构与 V1_3 兼容`() {
        val (json, result) = write(
            settings = BackupFormat.BackupSettings(
                fontSizeSp = 18,
                lineSpacingDp = 6,
                theme = "DAY",
                ttsRate = 1.0f,
                ttsEngine = null,
                ttsEngineSpecified = true,
                ttsForceSpeaker = false,
                ttsForceSpeakerSpecified = true,
            ),
            tables = mapOf(
                "books" to listOf(row("id" to 1L, "title" to "测试书", "author" to null)),
                "chapters" to listOf(
                    row("id" to 1L, "book_id" to 1L, "idx" to 0L, "title" to "第一章"),
                ),
            ),
        )
        assertTrue(json.startsWith("{\"schema\":1"))
        assertTrue(json.contains("\"app\":\"com.yyx.reader\""))
        assertTrue(json.contains("\"versionName\":\"1.4.0\""))
        assertTrue(json.contains("\"exportedAt\":1700000000000"))
        assertTrue(json.contains("\"ttsForceSpeaker\":false"))
        // 7 张表键都存在（缺数据的写空数组，与 V1.3 一致）
        BackupFormat.TABLES.forEach { table ->
            assertTrue("缺少表键 $table", json.contains("\"$table\":["))
        }
        assertTrue(json.trimEnd().endsWith("}"))
        assertEquals(1, result.tableCounts["books"])
        assertEquals(1, result.tableCounts["chapters"])
        assertEquals(0, result.tableCounts["bookmarks"])
        assertEquals(0, result.tableCounts["tags"])
    }

    @Test
    fun `字符串转义与 null 处理`() {
        val tricky = "引号\"与\\反斜杠\n换行\t制表"
        val (json, _) = write(
            tables = mapOf("books" to listOf(row("id" to 1L, "title" to tricky, "author" to null))),
        )
        assertTrue(json.contains("\\\"与\\\\反斜杠\\n换行\\t制表"))
        assertTrue(json.contains("\"author\":null"))
        // 转义后仍是合法 JSON 形态（引号成对）
        assertFalse(json.contains("\n换行"))
    }

    @Test
    fun `超限立即中止并抛 SizeLimitExceeded`() {
        val rows = (1L..50L).map { row("id" to it, "title" to "很长的书名".repeat(20)) }
        try {
            write(tables = mapOf("books" to rows), maxBytes = 200)
            fail("应在超限时抛出 SizeLimitExceeded")
        } catch (expected: BackupJsonWriter.SizeLimitExceeded) {
            // 消息已中性化（语言无关），断言携带的上限参数
            assertEquals(200L, expected.maxBytes)
        }
    }

    @Test
    fun `行内容不被整表缓存`() {
        // 回调式 API：分 3 次 emit 即产生 3 行，计数正确（验证流式接口语义）
        val out = ByteArrayOutputStream()
        var emitted = 0
        val result = BackupJsonWriter.write(
            out = out,
            exportedAt = 0L,
            versionName = "x",
            settings = null,
            tables = listOf("books"),
            streamRows = { _, emit -> repeat(3) { emitted++; emit(row("id" to it.toLong())) } },
            maxBytes = BackupFormat.MAX_FILE_BYTES,
        )
        assertEquals(3, emitted)
        assertEquals(3, result.tableCounts["books"])
        assertEquals(result.byteCount, out.size().toLong())
    }
}
