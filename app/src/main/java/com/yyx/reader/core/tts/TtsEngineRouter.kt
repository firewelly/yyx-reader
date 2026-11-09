package com.yyx.reader.core.tts

import javax.inject.Inject
import javax.inject.Singleton

/**
 * TTS 引擎路由门面（V1.5）：按用户选择在「系统引擎」与「内置离线引擎（sherpa-onnx）」间切换。
 *
 * 选择值与 DataStore `tts_engine` 复用同一字符串（接口不变、备份格式不变）：
 * - null → 系统引擎自动解析（系统默认 → 首个已安装，SystemTtsEngine 原语义）
 * - [LOCAL_ENGINE_ID]（保留值 "local:sherpa-onnx"）→ 内置离线引擎
 * - 其他 → 系统引擎指定包名
 *
 * listener 同时挂到两个引擎：只有活跃引擎会产生回调，切换引擎无需服务层重新挂监听。
 */
@Singleton
class TtsEngineRouter @Inject constructor(
    private val system: SystemTtsEngine,
    private val local: LocalTtsEngine,
) : TtsEngine {

    @Volatile private var choice: String? = null

    override val displayName: String
        get() = active().displayName

    override var listener: TtsEngine.Listener?
        get() = active().listener
        set(value) {
            system.listener = value
            local.listener = value
        }

    /** 当前活跃引擎是否为内置离线引擎（服务/设置页据此分流错误文案与输出路由策略） */
    val isLocalActive: Boolean
        get() = choice == LOCAL_ENGINE_ID

    /** 设置引擎选择（语义同 SystemTtsEngine.useEngine：切换即丢弃旧引擎会话状态） */
    fun useEngine(choice: String?) {
        if (this.choice == choice) return
        val old = active()
        this.choice = choice
        // 系统引擎侧的子选择：本地引擎激活时回退自动，包名选择仅在系统引擎生效
        system.useEngine(if (choice == null || choice == LOCAL_ENGINE_ID) null else choice)
        if (active() !== old) old.stop()
    }

    /** 强制外放：仅本地引擎消费（AudioTrack.setPreferredDevice 设备级路由）。
     *  系统引擎的强制外放由 TtsPlaybackService 的 isSpeakerphoneOn 降级接管负责。 */
    fun setForceSpeaker(enabled: Boolean) {
        local.setForceSpeaker(enabled)
    }

    /** 首选输出设备（V2 精确音频路由）：仅本地引擎消费；null = 跟随系统 */
    fun setPreferredOutput(routeKey: String?) {
        local.setPreferredOutput(routeKey)
    }

    /** 模型导入/移除后调用：丢弃本地引擎已加载状态，下次 init 重新探测加载 */
    fun invalidateLocalEngine() {
        local.shutdown()
    }

    override fun init(onReady: (Boolean) -> Unit) = active().init(onReady)

    override fun speak(text: String, utteranceId: String) = active().speak(text, utteranceId)

    override fun prepare(text: String) = active().prepare(text)

    override fun stop() = active().stop()

    override fun setSpeechRate(rate: Float) {
        system.setSpeechRate(rate)
        local.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        system.setPitch(pitch)
        local.setPitch(pitch)
    }

    override fun shutdown() {
        system.shutdown()
        local.shutdown()
    }

    private fun active(): TtsEngine = if (choice == LOCAL_ENGINE_ID) local else system

    companion object {
        /** tts_engine 中的保留值：使用内置离线引擎（sherpa-onnx） */
        const val LOCAL_ENGINE_ID = "local:sherpa-onnx"
    }
}
