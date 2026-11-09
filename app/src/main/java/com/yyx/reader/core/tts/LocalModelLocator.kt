package com.yyx.reader.core.tts

/**
 * 离线语音模型目录探测（V1.5，AGENTS.md 待办 #6）——纯 JVM 无 Android 依赖，可单元测试。
 *
 * 用户从 sherpa-onnx 官方模型库（HuggingFace csukuangfj 系列）下载模型并解压后，
 * 通过 SAF 选择目录导入。这里按「目录内相对路径集合」识别模型类型并校验必备文件：
 * - MeloTTS 中文（vits-melo-tts-zh_en 等）：model(.int8).onnx + tokens.txt + lexicon.txt + dict/（推荐导入）
 * - Matcha 中文（matcha-icefall-zh-baker 等）：model.onnx + vocoder.onnx + tokens.txt + lexicon.txt(+dict/)
 * - Kokoro 多语言（kokoro-multi-lang-v1_x）：model.onnx + tokens.txt + voices.bin + espeak-ng-data/
 *
 * 注意：探测只看相对路径集合，不读文件内容；大小校验由导入流程负责。
 */
object LocalModelLocator {

    /** 模型族标签（界面层映射资源，支持多语言） */
    enum class ModelLabel { VITS_ZH_INT8, VITS_ZH, MATCHA_ZH, MATCHA_EN, KOKORO }

    /** 探测错误类型（界面层映射资源文案；[detail] 为补充参数，如已找到的文件列表） */
    enum class LocateError { EMPTY_DIR, MISSING_MODEL, MISSING_TOKENS, MATCHA_MISSING_DATA, VITS_MISSING_DICT, UNRECOGNIZED }

    /** 探测失败异常：携带错误类型与补充参数，文案由界面层按语言解析 */
    class LocateException(val error: LocateError, val detail: String? = null) :
        IllegalArgumentException("${error.name}${detail?.let { ": $it" } ?: ""}")

    /** 探测结果：构造 sherpa-onnx OfflineTtsConfig 所需的全部原料（相对路径） */
    data class ModelLayout(
        /** 模型族：vits / matcha / kokoro */
        val type: String,
        /** 主模型文件（存在 int8 量化版时优先，体积小一半以上） */
        val modelFile: String,
        val tokensFile: String,
        /** 发音词典（中文 vits/matcha）；piper 等英文模型为 null */
        val lexiconFile: String?,
        /** jieba 分词目录（中文多音字依赖）；null = 无 */
        val dictDir: String?,
        /** matcha 声码器 */
        val vocoderFile: String?,
        /** kokoro 音色文件（voices.bin） */
        val voicesFile: String?,
        /** espeak-ng-data 音素数据目录（kokoro/piper） */
        val espeakDataDir: String?,
        /** 数字/日期/电话号码归一化规则 fst（中文模型普遍附带） */
        val ruleFsts: List<String>,
        /** 模型族标签（多语言由界面层映射 strings.xml 的 model_label_* 资源） */
        val label: ModelLabel,
    )

    /**
     * 从目录内相对路径集合探测模型布局。
     * [files] 形如 "model.int8.onnx"、"tokens.txt"、"dict/jieba.dict.utf8"。
     * 失败（Result.failure）时 message 为面向用户的中文原因。
     */
    fun locate(files: Set<String>): Result<ModelLayout> {
        val names = files.map { it.trimStart('/') }.toSet()
        if (names.isEmpty()) {
            return Result.failure(LocateException(LocateError.EMPTY_DIR))
        }

        val model = preferInt8(names, "model.onnx")
        if (model == null) {
            return Result.failure(LocateException(LocateError.MISSING_MODEL, preview(names)))
        }
        if ("tokens.txt" !in names) {
            return Result.failure(LocateException(LocateError.MISSING_TOKENS, preview(names)))
        }

        val ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst").filter { it in names }
        val espeakData = names.firstOrNull { it.startsWith("espeak-ng-data/") }
        val vocoder = listOf("vocoder.onnx").firstOrNull { it in names }
            ?: names.firstOrNull { it.endsWith("vocoder.onnx") }

        return when {
            // matcha：声学模型 + 声码器成对出现
            vocoder != null -> {
                val lexicon = if ("lexicon.txt" in names) "lexicon.txt" else null
                val dict = if (names.any { it.startsWith("dict/") }) "dict" else null
                if (lexicon == null && espeakData == null) {
                    Result.failure(LocateException(LocateError.MATCHA_MISSING_DATA))
                } else {
                    Result.success(
                        ModelLayout(
                            type = "matcha", modelFile = model, tokensFile = "tokens.txt",
                            lexiconFile = lexicon, dictDir = dict, vocoderFile = vocoder,
                            voicesFile = null, espeakDataDir = espeakData, ruleFsts = ruleFsts,
                            label = if (lexicon != null) ModelLabel.MATCHA_ZH else ModelLabel.MATCHA_EN,
                        ),
                    )
                }
            }

            // kokoro：音色 + espeak-ng-data 音素数据
            espeakData != null && names.any { it == "voices.bin" || it.endsWith("/voices.bin") } -> {
                val lexicon = if ("lexicon.txt" in names) "lexicon.txt" else null
                Result.success(
                    ModelLayout(
                        type = "kokoro", modelFile = model, tokensFile = "tokens.txt",
                        lexiconFile = lexicon, dictDir = null, vocoderFile = null,
                        voicesFile = names.first { it == "voices.bin" || it.endsWith("/voices.bin") },
                        espeakDataDir = espeakData.substringBefore('/'), ruleFsts = ruleFsts,
                        label = ModelLabel.KOKORO,
                    ),
                )
            }

            // 中文 vits（MeloTTS / vits-zh-hf 系列）：词典 + jieba 分词目录
            "lexicon.txt" in names -> {
                if (!names.any { it.startsWith("dict/") }) {
                    Result.failure(LocateException(LocateError.VITS_MISSING_DICT))
                } else {
                    Result.success(
                        ModelLayout(
                            type = "vits", modelFile = model, tokensFile = "tokens.txt",
                            lexiconFile = "lexicon.txt", dictDir = "dict", vocoderFile = null,
                            voicesFile = null, espeakDataDir = null, ruleFsts = ruleFsts,
                            label = if (model.endsWith(".int8.onnx")) ModelLabel.VITS_ZH_INT8 else ModelLabel.VITS_ZH,
                        ),
                    )
                }
            }

            else -> Result.failure(LocateException(LocateError.UNRECOGNIZED, preview(names)))
        }
    }

    /** 同名模型存在 int8 量化版时优先（体积小 ~60%，质量损失可忽略） */
    private fun preferInt8(names: Set<String>, base: String): String? {
        val int8 = base.removeSuffix(".onnx") + ".int8.onnx"
        return when {
            int8 in names -> int8
            base in names -> base
            else -> null
        }
    }

    /** 错误详情里最多列 8 个文件，避免对话框刷屏（纯数据，不含语言相关文案） */
    private fun preview(names: Set<String>): String =
        names.sorted().take(8).joinToString(", ") + if (names.size > 8) ", …(${names.size})" else ""
}
