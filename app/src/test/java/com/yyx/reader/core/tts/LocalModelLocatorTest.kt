package com.yyx.reader.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线语音模型目录探测测试（V1.5）：覆盖 MeloTTS 中文（int8/fp32）、Matcha、Kokoro
 * 三类布局与各类缺文件/目录为空的失败路径。
 */
class LocalModelLocatorTest {

    @Test
    fun `melo 中文模型 int8 优先于 fp32`() {
        val files = setOf(
            "model.onnx", "model.int8.onnx", "tokens.txt", "lexicon.txt",
            "dict/jieba.dict.utf8", "dict/idf.utf8",
            "date.fst", "number.fst", "phone.fst", "new_heteronym.fst", "README.md",
        )
        val layout = LocalModelLocator.locate(files).getOrThrow()
        assertEquals("vits", layout.type)
        assertEquals("model.int8.onnx", layout.modelFile)
        assertEquals("lexicon.txt", layout.lexiconFile)
        assertEquals("dict", layout.dictDir)
        assertNull(layout.vocoderFile)
        assertEquals(listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst"), layout.ruleFsts)
        assertEquals(LocalModelLocator.ModelLabel.VITS_ZH_INT8, layout.label)
    }

    @Test
    fun `melo 中文模型仅有 fp32 时用 fp32`() {
        val files = setOf("model.onnx", "tokens.txt", "lexicon.txt", "dict/jieba.dict.utf8")
        val layout = LocalModelLocator.locate(files).getOrThrow()
        assertEquals("model.onnx", layout.modelFile)
        assertEquals("vits", layout.type)
    }

    @Test
    fun `matcha 布局识别声码器`() {
        val files = setOf(
            "model.onnx", "vocoder.onnx", "tokens.txt", "lexicon.txt", "dict/jieba.dict.utf8", "number.fst",
        )
        val layout = LocalModelLocator.locate(files).getOrThrow()
        assertEquals("matcha", layout.type)
        assertEquals("vocoder.onnx", layout.vocoderFile)
        assertEquals("dict", layout.dictDir)
        assertEquals(listOf("number.fst"), layout.ruleFsts)
    }

    @Test
    fun `matcha 缺词典与 espeak 时拒绝`() {
        val files = setOf("model.onnx", "vocoder.onnx", "tokens.txt")
        val err = LocalModelLocator.locate(files).exceptionOrNull()
        assertEquals(LocalModelLocator.LocateError.MATCHA_MISSING_DATA, (err as LocalModelLocator.LocateException).error)
    }

    @Test
    fun `kokoro 布局识别音色与 espeak 数据`() {
        val files = setOf(
            "model.onnx", "tokens.txt", "voices.bin", "espeak-ng-data/lang/file", "lexicon.txt",
        )
        val layout = LocalModelLocator.locate(files).getOrThrow()
        assertEquals("kokoro", layout.type)
        assertEquals("voices.bin", layout.voicesFile)
        assertEquals("espeak-ng-data", layout.espeakDataDir)
    }

    @Test
    fun `缺 tokens 拒绝并提示文件名`() {
        val files = setOf("model.onnx", "lexicon.txt", "dict/x")
        val err = LocalModelLocator.locate(files).exceptionOrNull() as LocalModelLocator.LocateException
        assertEquals(LocalModelLocator.LocateError.MISSING_TOKENS, err.error)
        assertTrue(err.detail!!.contains("model.onnx"))
    }

    @Test
    fun `缺模型拒绝并提示 model onnx`() {
        val files = setOf("tokens.txt", "lexicon.txt", "dict/x")
        val err = LocalModelLocator.locate(files).exceptionOrNull() as LocalModelLocator.LocateException
        assertEquals(LocalModelLocator.LocateError.MISSING_MODEL, err.error)
        assertTrue(err.detail!!.contains("tokens.txt"))
    }

    @Test
    fun `中文 vits 缺 dict 目录拒绝`() {
        val files = setOf("model.onnx", "tokens.txt", "lexicon.txt")
        val err = LocalModelLocator.locate(files).exceptionOrNull()
        assertEquals(LocalModelLocator.LocateError.VITS_MISSING_DICT, (err as LocalModelLocator.LocateException).error)
    }

    @Test
    fun `空目录拒绝`() {
        val err = LocalModelLocator.locate(emptySet()).exceptionOrNull()
        assertEquals(LocalModelLocator.LocateError.EMPTY_DIR, (err as LocalModelLocator.LocateException).error)
    }

    @Test
    fun `无法识别的目录拒绝并列出已见文件`() {
        val files = setOf("model.onnx", "tokens.txt", "foo.bin")
        val err = LocalModelLocator.locate(files).exceptionOrNull() as LocalModelLocator.LocateException
        assertEquals(LocalModelLocator.LocateError.UNRECOGNIZED, err.error)
        assertTrue(err.detail!!.contains("foo.bin"))
    }
}
