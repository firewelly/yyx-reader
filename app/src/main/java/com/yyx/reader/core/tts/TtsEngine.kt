package com.yyx.reader.core.tts

/** TTS 引擎抽象（架构文档 §5.6.1）：系统引擎与内置离线引擎共用同一接口 */
interface TtsEngine {

    /** 引擎显示名 */
    val displayName: String

    /** 初始化（异步），完成后回调；重复调用幂等 */
    fun init(onReady: (Boolean) -> Unit)

    /** 朗读一句；utteranceId 由调用方构造，用于进度回写 */
    fun speak(text: String, utteranceId: String)

    /**
     * 预取一句（可选能力，默认空实现）：服务在 speak 当前句后立即调用，
     * 本地引擎在播放当前句的同时于后台合成下一句，消除句间合成空档；系统引擎忽略。
     */
    fun prepare(text: String) {}

    fun stop()

    fun setSpeechRate(rate: Float)
    fun setPitch(pitch: Float)

    fun shutdown()

    var listener: Listener?

    interface Listener {
        fun onUtteranceStart(utteranceId: String)
        fun onUtteranceDone(utteranceId: String)
        fun onUtteranceError(utteranceId: String)
    }

    companion object {
        /** utteranceId 编码句信息：s:<sentenceIdx>:<charStart> */
        fun utteranceId(sentenceIdx: Int, charStart: Int): String = "s:$sentenceIdx:$charStart"

        /** 解析 utteranceId；非法返回 null */
        fun parse(utteranceId: String): Pair<Int, Int>? {
            if (!utteranceId.startsWith("s:")) return null
            val parts = utteranceId.split(":")
            if (parts.size != 3) return null
            val idx = parts[1].toIntOrNull() ?: return null
            val offset = parts[2].toIntOrNull() ?: return null
            return idx to offset
        }
    }
}
