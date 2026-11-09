package com.yyx.reader.core.tts

import com.yyx.reader.R
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.yyx.reader.core.audio.matchesRouteKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置离线 TTS 引擎（V1.5，AGENTS.md 待办 #6）：sherpa-onnx 本地模型合成 + AudioTrack 自行播放。
 *
 * 与 [SystemTtsEngine] 共用 [TtsEngine] 接口与 utteranceId 协议，句级回调语义一致：
 * - **预取管线（V1.5.x 真机优化）**：两个单线程执行器——合成线程与播放线程。
 *   服务在 speak(N) 后立刻 prepare(N+1)：播放第 N 句的同时后台合成第 N+1 句，
 *   下一句 speak 直接命中缓存起播，句间空档从「合成耗时」降到接近 0。
 * - **整句缓冲播放**：先取到完整音频（缓存命中或阻塞生成），预灌一块缓冲后起播，
 *   再以内存拷贝速度写完剩余，避免流式供流在真机 HAL 上偶发 underrun（曾致首轨停滞）。
 * - stop：置中断标志 + pause/flush AudioTrack（立即静音；合成至多浪费一句，结果作废）。
 * - setSpeechRate 映射 sherpa 的 speed（缓存音频带语速标记，变速自动失效）；setPitch 无对应能力，忽略。
 * - 输出路由：AudioTrack.setPreferredDevice 定向首选设备（V2 精确路由）/内置扬声器（强制外放）。
 *
 * 模型不内置 APK：用户经 SAF 导入到固定目录 [modelDir]，init 时探测加载；缺失/无效则 init(false)。
 */
@Singleton
class LocalTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsEngine {

    override val displayName: String = "内置离线引擎"

    /** 合成专用单线程：模型加载、prepare 预取、（缺缓存时）speak 的阻塞生成，全部串行化 */
    private val synthExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yyx-local-tts-synth").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    /** 播放专用单线程：AudioTrack 创建/写入/等待播完，与合成线程并行 */
    private val playExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yyx-local-tts-play")
    }

    private var tts: OfflineTts? = null
    private var ready = false

    /** 模型加载中（加载耗时数秒，期间后续 init 合并到 pendingInit） */
    @Volatile private var initInFlight = false
    private var pendingInit: ((Boolean) -> Unit)? = null

    @Volatile private var speechRate = 1.0f

    /** forceSpeaker（由服务层经 TtsEngineRouter 注入）：每句建 AudioTrack 时应用扬声器路由 */
    @Volatile private var forceSpeaker = false

    /** 首选输出设备选择器（V2 精确音频路由）：t<type>[:<address>]；null = 跟随系统。优先于 forceSpeaker */
    @Volatile private var preferredOutput: String? = null

    /** 当前朗读任务的中断标志；stop() 置位，speak() 开头换新实例 */
    @Volatile private var stopFlag: AtomicBoolean? = null

    /** 当前播放中的 AudioTrack，stop() 用于立即静音 */
    @Volatile private var currentTrack: AudioTrack? = null

    /** 预取缓存（单句，含生成时语速用于失效判定）；跨线程访问用 @Volatile 保证可见性 */
    @Volatile private var prepared: PreparedAudio? = null

    override var listener: TtsEngine.Listener? = null

    /** 一句完整合成音频 */
    private class PreparedAudio(
        val text: String,
        val speed: Float,
        val samples: FloatArray,
        val sampleRate: Int,
        /** 生成耗时（ms），仅用于日志/RTF 观测 */
        val genMs: Long,
    )

    override fun init(onReady: (Boolean) -> Unit) {
        if (ready) {
            onReady(true)
            return
        }
        if (initInFlight) {
            pendingInit = onReady
            return
        }
        val modelDir = modelDir(context)
        val layout = installedLayout(context)
        if (layout == null) {
            Log.i(TAG, "本地引擎 init 失败：$modelDir 无有效语音模型")
            onReady(false)
            return
        }
        initInFlight = true
        pendingInit = onReady // 首次 init 的回调统一走 pendingInit 通道，任务完成时统一派发
        Log.i(TAG, "本地引擎开始加载模型：${layout.label}（$modelDir）")
        synthExecutor.execute {
            var ok = false
            try {
                val abs = { rel: String -> File(modelDir, rel).absolutePath }
                val modelCfg = when (layout.type) {
                    "matcha" -> OfflineTtsModelConfig(
                        matcha = OfflineTtsMatchaModelConfig(
                            acousticModel = abs(layout.modelFile),
                            vocoder = abs(layout.vocoderFile!!),
                            lexicon = layout.lexiconFile?.let(abs) ?: "",
                            tokens = abs(layout.tokensFile),
                            dictDir = layout.dictDir?.let(abs) ?: "",
                        ),
                        numThreads = NUM_THREADS,
                        provider = "cpu",
                    )

                    "kokoro" -> OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = abs(layout.modelFile),
                            voices = abs(layout.voicesFile!!),
                            tokens = abs(layout.tokensFile),
                            lexicon = layout.lexiconFile?.let(abs) ?: "",
                            dataDir = layout.espeakDataDir?.let(abs) ?: "",
                        ),
                        numThreads = NUM_THREADS,
                        provider = "cpu",
                    )

                    else -> OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = abs(layout.modelFile),
                            lexicon = abs(layout.lexiconFile!!),
                            tokens = abs(layout.tokensFile),
                            dictDir = layout.dictDir?.let(abs) ?: "",
                        ),
                        numThreads = NUM_THREADS,
                        provider = "cpu",
                    )
                }
                val config = OfflineTtsConfig(
                    model = modelCfg,
                    ruleFsts = layout.ruleFsts.joinToString(",") { abs(it) },
                )
                val engine = OfflineTts(config = config)
                tts = engine
                ok = true
                Log.i(TAG, "本地引擎加载完成 sampleRate=${engine.sampleRate()} lang=${engine.lang()}")
            } catch (t: Throwable) {
                Log.w(TAG, "本地引擎模型加载失败（${layout.label}）", t)
            }
            ready = ok
            initInFlight = false
            if (!ok) {
                runCatching { tts?.release() }
                tts = null
            }
            pendingInit?.invoke(ok)
            pendingInit = null
        }
    }

    /**
     * 预取下一句：合成线程后台生成完整音频存入单句缓存。重复调用幂等（同文本同语速直接返回）。
     * 服务在 speak 后立即调用；播放第 N 句期间生成第 N+1 句，消除句间合成空档。
     */
    override fun prepare(text: String) {
        if (!ready || text.isBlank()) return
        val speed = speechRate
        prepared?.let { if (it.text == text && it.speed == speed) return }
        synthExecutor.execute { generateToCache(text, speed) }
    }

    override fun speak(text: String, utteranceId: String) {
        if (!ready) return
        val flag = AtomicBoolean(false)
        stopFlag = flag
        playExecutor.execute { speakOnPlayThread(text, utteranceId, flag) }
    }

    /** 取音频：缓存命中直接返回；否则在合成线程阻塞生成（与 prepare 串行，保证线程安全） */
    private fun obtainAudio(text: String): PreparedAudio? {
        val speed = speechRate
        prepared?.let {
            if (it.text == text && it.speed == speed) {
                prepared = null // 消费掉
                Log.i(TAG, "预取命中（${text.length} 字，音频 ${"%.2f".format(it.samples.size.toFloat() / it.sampleRate)}s）")
                return it
            }
        }
        val future = synthExecutor.submit(
            java.util.concurrent.Callable<PreparedAudio?> { generateToCache(text, speechRate) },
        )
        return runCatching { future.get(15, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
    }

    /** 在合成线程生成完整音频并写入缓存（结果同时作为返回值） */
    private fun generateToCache(text: String, speed: Float): PreparedAudio? {
        val engine = tts ?: return null
        if (!ready) return null
        val t0 = System.currentTimeMillis()
        return try {
            val audio = engine.generate(text, sid = 0, speed = speed)
            val genMs = System.currentTimeMillis() - t0
            if (audio.samples.isEmpty()) return null
            val result = PreparedAudio(text, speed, audio.samples, audio.sampleRate, genMs)
            // 语速在生成期间变化则丢弃，避免缓存与当前设置不符
            if (speed == speechRate) prepared = result
            Log.i(
                TAG,
                "合成完成 ${text.length} 字 → 音频 ${"%.2f".format(audio.samples.size.toFloat() / audio.sampleRate)}s" +
                    "（生成 ${genMs}ms，RTF ${"%.2f".format(genMs / 1000f / (audio.samples.size.toFloat() / audio.sampleRate))}）",
            )
            result
        } catch (t: Throwable) {
            Log.w(TAG, "合成失败（${text.length} 字）", t)
            null
        }
    }

    private fun speakOnPlayThread(text: String, utteranceId: String, flag: AtomicBoolean) {
        var track: AudioTrack? = null
        try {
            listener?.onUtteranceStart(utteranceId)
            val audio = obtainAudio(text) ?: throw IllegalStateException("无可用合成音频")
            if (flag.get()) return
            val sampleRate = audio.sampleRate.coerceAtLeast(8_000)
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minBuf > 0) { "AudioTrack 最小缓冲区获取失败（minBuf=$minBuf）" }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf * 4, 256 * 1024))
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 初始化失败（state=${track.state}）" }
            // 输出路由（优先级）：首选设备（精确选择）> 强制外放（内置扬声器）> 跟随系统
            val route = resolveRoute(preferredOutput) ?: (if (forceSpeaker) speakerDevice() else null)
            if (route != null) {
                track.setPreferredDevice(route)
                Log.i(TAG, "输出路由已应用：type=${route.type} addr=${deviceAddress(route)}（preferred=${preferredOutput != null}）")
            }
            currentTrack = track

            // 1) 预灌缓冲（音频已在内存，写满一块缓冲只需毫秒级）：消除起播空缓冲导致的
            //    真机 HAL underrun / 首轨 disabled（曾表现为首句播放头停滞 5s 被停滞守卫跳过）
            val samples = audio.samples
            var written = 0
            while (!flag.get() && written < samples.size) {
                val w = track.write(samples, written, samples.size - written, AudioTrack.WRITE_NON_BLOCKING)
                if (w <= 0) break // 缓冲已满（w<0 为错误，交给下面统一判断）
                written += w
            }
            if (flag.get()) return
            track.play()
            // 2) 起播后写完剩余（内存 → 混音器，拷贝速度远超实时，不会再 underrun）
            while (!flag.get() && written < samples.size) {
                val w = track.write(samples, written, samples.size - written, AudioTrack.WRITE_NON_BLOCKING)
                when {
                    w > 0 -> written += w
                    w < 0 -> throw IllegalStateException("AudioTrack 写入失败（code=$w）")
                    else -> Thread.sleep(10)
                }
            }
            // 3) 等缓冲区播完（drain），stop 置标志则立即退出。
            // 停滞守卫：音频输出不可用（无头模拟器/无声卡设备）时 head 全程为 0，
            // 连续约 5s 无推进即视为播完，避免朗读卡死（真机正常播放时 head 实时前进）。
            var lastHead = -1L
            var stallTicks = 0
            while (!flag.get()) {
                val head = track.playbackHeadPosition.toLong()
                if (head >= samples.size) break
                if (head == lastHead) {
                    stallTicks++
                    if (stallTicks > DRAIN_STALL_TICKS) {
                        Log.w(TAG, "播放头 $stallTicks 周期无推进（frames=${samples.size} head=$head），判定输出不可用，跳至句末")
                        break
                    }
                } else {
                    stallTicks = 0
                    lastHead = head
                }
                Thread.sleep(60)
            }
            if (!flag.get()) listener?.onUtteranceDone(utteranceId)
        } catch (t: Throwable) {
            Log.w(TAG, "本地引擎朗读失败 utteranceId=$utteranceId", t)
            if (!flag.get()) listener?.onUtteranceError(utteranceId)
        } finally {
            currentTrack = null
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
        }
    }

    override fun stop() {
        stopFlag?.set(true)
        runCatching { currentTrack?.pause() }
        runCatching { currentTrack?.flush() }
    }

    override fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 3.0f)
        if (clamped != speechRate) {
            speechRate = clamped
            prepared = null // 语速变化：预取音频失效
        }
    }

    override fun setPitch(pitch: Float) {
        // sherpa-onnx 离线合成无音调参数，忽略（系统引擎支持 0.5x–2x；设置页未暴露音调，无感知差异）
    }

    override fun shutdown() {
        stop()
        pendingInit = null
        ready = false
        initInFlight = false
        prepared = null
        synthExecutor.execute {
            runCatching { tts?.release() }
            tts = null
        }
    }

    /** forceSpeaker 路由开关（TtsEngineRouter 转发，服务层订阅设置后调用） */
    fun setForceSpeaker(enabled: Boolean) {
        forceSpeaker = enabled
    }

    /** 首选输出设备选择器（TtsEngineRouter 转发）；null = 跟随系统。按选择器在每次建轨时实时解析设备 */
    fun setPreferredOutput(routeKey: String?) {
        preferredOutput = routeKey?.takeIf { it.isNotBlank() }
    }

    /** AudioTrack 设备级路由：内置扬声器（找不为 null 即认为可接管） */
    private fun speakerDevice(): AudioDeviceInfo? =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** 按持久化选择器解析当前在线设备；选择器为空或设备不在线返回 null（跟随系统） */
    private fun resolveRoute(routeKey: String?): AudioDeviceInfo? {
        if (routeKey.isNullOrBlank()) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { info ->
            matchesRouteKey(info.type, deviceAddress(info), routeKey)
        }
    }

    /** [AudioDeviceInfo.getAddress] 需 API 28；26/27 返回空串（路由仅按类型匹配） */
    private fun deviceAddress(info: AudioDeviceInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address.orEmpty() else ""

    companion object {
        private const val TAG = "YYXLocalTts"

        /**
         * ONNX 推理线程数（V1.5.x 真机调优）：真机实测 2 线程 RTF≈1.25（合成比实时慢），
         * 而 CPU 只吃到 ~1.1 核 → 提高到 4 线程（上限 4，兼顾发热/耗电）。
         */
        private val NUM_THREADS: Int = minOf(4, Runtime.getRuntime().availableProcessors())

        /** drain 停滞守卫：83 × 60ms ≈ 5s 无播放头推进即判定输出不可用 */
        private const val DRAIN_STALL_TICKS = 83

        /** 模型固定安装目录：files/tts_model（设备本地，不参与备份——模型文件可随时重新导入） */
        const val MODEL_DIR_NAME = "tts_model"

        fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

        /** 当前已安装模型的布局；未导入 / 目录损坏返回 null */
        fun installedLayout(context: Context): LocalModelLocator.ModelLayout? = installedLayoutAt(modelDir(context))

        /** 任意目录的模型布局探测（导入流程用于拷贝后复检临时目录） */
        fun installedLayoutAt(dir: File): LocalModelLocator.ModelLayout? {
            if (!dir.isDirectory) return null
            val files = dir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
            return runCatching { LocalModelLocator.locate(files).getOrNull() }.getOrNull()
        }
    }
}
