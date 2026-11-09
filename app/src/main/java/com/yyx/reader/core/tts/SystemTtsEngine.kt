package com.yyx.reader.core.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 已安装 TTS 引擎描述 */
data class InstalledEngine(val packageName: String, val label: String)

/**
 * 系统 TTS 引擎（AOSP android.speech.tts 框架 API）。
 * 引擎无关：不绑定 Google TTS，用户可在设置中选择任意已安装引擎（eSpeak NG / RHVoice / 厂商引擎）。
 */
@Singleton
class SystemTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsEngine {

    override val displayName: String = "系统 TTS"

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingInit: ((Boolean) -> Unit)? = null
    private var speechRate = 1.0f
    private var pitch = 1.0f

    /** 用户指定引擎包名；null = 自动解析 */
    private var enginePackage: String? = null

    override var listener: TtsEngine.Listener? = null

    override fun init(onReady: (Boolean) -> Unit) {
        if (ready) {
            onReady(true)
            return
        }
        if (tts != null) {
            pendingInit = onReady
            return
        }
        // 显式解析引擎包名：无默认引擎设置的设备（AOSP/国产 ROM 常见）必须手动挑选
        val chosen = resolveEnginePackage()
        Log.i(TAG, "TTS 引擎列表=${listEngines(context)} → 选择: ${chosen ?: "无"}")
        if (chosen == null) {
            onReady(false)
            return
        }
        pendingInit = onReady
        tts = TextToSpeech(context, { status ->
            ready = status == TextToSpeech.SUCCESS
            Log.i(TAG, "TTS init 完成 engine=$chosen ready=$ready")
            if (!ready) {
                // 失败必须清空，否则后续 init 永远卡在 pending 分支无法重试
                runCatching { tts?.shutdown() }
                tts = null
            } else {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(pitch)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let { listener?.onUtteranceStart(it) }
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { listener?.onUtteranceDone(it) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { listener?.onUtteranceError(it) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let { listener?.onUtteranceError(it) }
                    }
                })
            }
            pendingInit?.invoke(ready)
            pendingInit = null
        }, chosen)
    }

    /** 用户指定的引擎包名（null = 自动解析），设置页切换用 */
    fun useEngine(packageName: String?) {
        if (enginePackage == packageName) return
        shutdown()
        enginePackage = packageName
    }

    private fun resolveEnginePackage(): String? {
        enginePackage?.let { return it }
        val engines = listEngines(context)
        if (engines.isEmpty()) return null
        // 系统默认引擎（若存在且有效），否则取第一个
        val default = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "tts_default_synth",
            )
        }.getOrNull()
        return if (default != null && engines.any { it.packageName == default }) default else engines.first().packageName
    }

    override fun speak(text: String, utteranceId: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 3.0f)
        tts?.setSpeechRate(speechRate)
    }

    override fun setPitch(p: Float) {
        pitch = p.coerceIn(0.5f, 2.0f)
        tts?.setPitch(pitch)
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "YYXTtsEngine"

        /** 列出设备已安装的 TTS 引擎（AOSP 查询，不依赖 GMS）。
         *  TTS_SERVICE 由引擎的 Service 声明，必须用 queryIntentServices（框架 TtsEngines 同款）。 */
        fun listEngines(context: Context): List<InstalledEngine> =
            runCatching {
                val pm = context.packageManager
                val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                pm.queryIntentServices(intent, 0).map { ri ->
                    InstalledEngine(
                        packageName = ri.serviceInfo.packageName,
                        label = ri.loadLabel(pm)?.toString() ?: ri.serviceInfo.packageName,
                    )
                }
            }.getOrDefault(emptyList())
    }
}
