package com.yyx.reader.service.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yyx.reader.MainActivity
import com.yyx.reader.R
import com.yyx.reader.core.datastore.ReaderSettingsRepository
import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.BookPosition
import com.yyx.reader.core.domain.model.Chapter
import com.yyx.reader.core.domain.model.ProgressSource
import com.yyx.reader.core.domain.repository.BookRepository
import com.yyx.reader.core.domain.repository.ChapterRepository
import com.yyx.reader.core.domain.repository.ProgressRepository
import com.yyx.reader.core.ebook.ChapterTextSource
import com.yyx.reader.core.tts.SentenceSplitter
import com.yyx.reader.core.tts.TtsEngine
import com.yyx.reader.core.tts.TtsEngineRouter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 听书前台服务（架构文档 §5.6）：
 * - 句级播放循环：引擎回调驱动，每句开始即回写统一进度（source=LISTEN）
 * - MediaSessionCompat：通知栏/锁屏/耳机线控/蓝牙媒体键
 * - 音频焦点：抢占时暂停（保持暂停，用户手动恢复）；拔耳机自动暂停（F-08-04）
 */
@AndroidEntryPoint
class TtsPlaybackService : Service(), TtsPlaybackController.Executor {

    @Inject lateinit var controller: TtsPlaybackController
    @Inject lateinit var bookRepository: BookRepository
    @Inject lateinit var chapterRepository: ChapterRepository
    @Inject lateinit var progressRepository: ProgressRepository
    @Inject lateinit var textSource: ChapterTextSource

    /** 引擎路由门面（V1.5）：系统引擎 ↔ 内置离线引擎，由 tts_engine 选择值决定 */
    @Inject lateinit var engine: TtsEngineRouter
    @Inject lateinit var settingsRepository: ReaderSettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    // 播放上下文
    private var book: Book? = null
    private var chapters: List<Chapter> = emptyList()
    private var chapterIdx = 0
    private var sentences: List<SentenceSplitter.Sentence> = emptyList()
    private var sentenceIdx = 0
    private var sleepJob: kotlinx.coroutines.Job? = null

    /** true = 引擎朗读中（或等待回调）；pause/stop 时先置 false 以拦截 stop 引发的 onDone */
    @Volatile
    private var speaking = false

    private var notificationStarted = false

    // ---------- 输出路由（V1.3 F-08 降级实现） ----------
    /** forceSpeaker 设置缓存：onCreate 订阅 DataStore，播报前按此值接管音频路由 */
    @Volatile
    private var forceSpeaker = false

    /** 接管前的系统 isSpeakerphoneOn；null = 当前未接管（与 [savedAudioMode] 成对保存/恢复） */
    private var savedSpeakerphoneOn: Boolean? = null

    /** 接管前的系统 AudioManager.mode；与 [savedSpeakerphoneOn] 成对保存/恢复 */
    private var savedAudioMode: Int? = null

    // ---------- 生命周期 ----------

    override fun onCreate() {
        super.onCreate()
        controller.executor = this
        createChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupSession()
        engine.listener = engineListener
        registerNoisy()
        observeForceSpeaker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TtsPlaybackController.ACTION_START -> {
                startForegroundNotify()
                start(
                    bookId = intent.getLongExtra(TtsPlaybackController.EXTRA_BOOK_ID, 0),
                    chapterIdx = intent.getIntExtra(TtsPlaybackController.EXTRA_CHAPTER, 0),
                    charOffset = intent.getIntExtra(TtsPlaybackController.EXTRA_OFFSET, 0),
                )
            }
            TtsPlaybackController.ACTION_PLAY_PAUSE -> if (controller.state.value.isPlaying) pause() else resume()
            TtsPlaybackController.ACTION_NEXT -> nextSentence()
            TtsPlaybackController.ACTION_PREV -> prevSentence()
            TtsPlaybackController.ACTION_NEXT_CH -> nextChapter()
            TtsPlaybackController.ACTION_PREV_CH -> prevChapter()
            TtsPlaybackController.ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onDestroy() {
        speaking = false
        restoreSystemRouting()
        engine.stop()
        engine.listener = null
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest ?: return) }
        noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        runCatching { session.release() }
        controller.executor = null
        controller.reset()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Executor ----------

    override fun start(bookId: Long, chapterIdx: Int, charOffset: Int) {
        android.util.Log.i("YYXTtsService", "start() bookId=$bookId ch=$chapterIdx offset=$charOffset")
        scope.launch {
            // 应用持久化的听书设置：引擎 + 语速（PRD F-09-02）
            val settings = settingsRepository.listenSettings.first()
            forceSpeaker = settings.forceSpeaker
            engine.useEngine(settings.enginePackage)
            engine.setSpeechRate(settings.rate)
            controller.updateState { it.copy(rate = settings.rate) }

            val b = bookRepository.getBook(bookId)
            if (b == null) {
                controller.updateState { it.copy(active = true, error = getString(R.string.tts_error_book_missing)) }
                return@launch
            }
            book = b
            chapters = chapterRepository.chapters(bookId)
            if (chapters.isEmpty()) {
                controller.updateState { it.copy(active = true, bookId = bookId, bookTitle = b.title, error = getString(R.string.tts_error_no_chapters)) }
                return@launch
            }
            controller.updateState {
                it.copy(active = true, bookId = bookId, bookTitle = b.title, error = null)
            }
            // 引擎初始化门控：无可用 TTS 引擎时给出安装引导（PRD F-06-03）
            engine.init { ready ->
                scope.launch {
                    if (!ready) {
                        controller.updateState {
                            it.copy(
                                engineReady = false,
                                isPlaying = false,
                                error = if (engine.isLocalActive) {
                                    getString(R.string.tts_error_local_model)
                                } else {
                                    getString(R.string.tts_error_no_engine)
                                },
                            )
                        }
                        return@launch
                    }
                    loadChapter(chapterIdx, charOffset)
                }
            }
        }
    }

    override fun resume() {
        if (sentences.isEmpty()) return
        speakFrom(sentenceIdx)
    }

    override fun pause() {
        speaking = false
        engine.stop()
        restoreSystemRouting()
        controller.updateState { it.copy(isPlaying = false) }
        updateSession(playing = false)
        refreshNotification()
    }

    override fun nextSentence() {
        speaking = false
        engine.stop()
        speakFrom(sentenceIdx + 1)
    }

    override fun prevSentence() {
        speaking = false
        engine.stop()
        speakFrom(sentenceIdx - 1)
    }

    override fun nextChapter() {
        speaking = false
        engine.stop()
        scope.launch { loadChapter(chapterIdx + 1, 0) }
    }

    override fun prevChapter() {
        speaking = false
        engine.stop()
        scope.launch { loadChapter(chapterIdx - 1, 0) }
    }

    override fun stopPlayback() {
        speaking = false
        engine.stop()
        restoreSystemRouting()
        sleepJob?.cancel()
        sleepJob = null
        notificationStarted = false
        controller.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun setRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 3.0f)
        engine.setSpeechRate(clamped)
        controller.updateState { it.copy(rate = clamped) }
        scope.launch { settingsRepository.setTtsRate(clamped) }
    }

    // ---------- 定时停止（PRD F-06-08） ----------

    override fun sleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        controller.updateState { it.copy(sleepTimerEndsAt = endAt, stopAtChapterEnd = false) }
        sleepJob = scope.launch {
            while (isActive) {
                if (System.currentTimeMillis() >= endAt) {
                    pause()
                    controller.updateState { it.copy(sleepTimerEndsAt = null) }
                    break
                }
                delay(30_000)
            }
        }
    }

    override fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        controller.updateState { it.copy(sleepTimerEndsAt = null, stopAtChapterEnd = false) }
    }

    override fun stopAtChapterEnd(enabled: Boolean) {
        sleepJob?.cancel()
        sleepJob = null
        controller.updateState { it.copy(stopAtChapterEnd = enabled, sleepTimerEndsAt = null) }
    }

    // ---------- 播放循环 ----------

    private suspend fun loadChapter(requestedIdx: Int, offset: Int) {
        val b = book ?: return
        if (chapters.isEmpty()) return
        val clamped = requestedIdx.coerceIn(0, chapters.size - 1)
        chapterIdx = clamped
        val chapter = chapters[clamped]
        val text = textSource.load(b, chapter)
        sentences = SentenceSplitter.split(text)
        controller.updateState {
            it.copy(
                chapterIdx = clamped,
                chapterTitle = chapter.title,
                sentenceCount = sentences.size,
                engineReady = sentences.isNotEmpty(),
                error = if (sentences.isEmpty()) getString(R.string.tts_error_empty_chapter) else null,
            )
        }
        if (sentences.isEmpty()) return
        // 定位起始句：第一个 end > offset 的句子（读→听入口，架构文档 §5.4）
        val startIdx = sentences.indexOfFirst { it.end > offset }.takeIf { it >= 0 } ?: 0
        speakFrom(startIdx)
    }

    private fun speakFrom(idx: Int) {
        // 章节边界：定时「本章结束即停」优先，否则自动续播下一章
        if (idx >= sentences.size) {
            val stopHere = controller.state.value.stopAtChapterEnd
            if (stopHere) {
                controller.updateState { it.copy(stopAtChapterEnd = false) }
                pause()
            } else if (chapterIdx < chapters.size - 1) {
                scope.launch { loadChapter(chapterIdx + 1, 0) }
            } else {
                pause()
            }
            return
        }
        if (idx < 0) {
            if (chapterIdx > 0) {
                scope.launch { loadChapter(chapterIdx - 1, Int.MAX_VALUE) }
            } else {
                speakFrom(0)
            }
            return
        }
        sentenceIdx = idx
        val sentence = sentences[idx]
        controller.updateState {
            it.copy(
                sentenceIdx = idx,
                currentSentence = sentence.text,
                charOffset = sentence.start,
                sentenceStart = sentence.start,
                sentenceEnd = sentence.end,
                isPlaying = true,
            )
        }
        // 进度回写：句开始（听到哪从哪继续）
        val bookId = book?.id ?: return
        scope.launch {
            progressRepository.update(BookPosition(bookId, chapterIdx, sentence.start), ProgressSource.LISTEN)
        }
        // V1.5：本地引擎在 AudioTrack 上做设备级路由（setPreferredDevice），无需 speakerphone 接管；
        // 系统引擎沿用 V1.3 的 isSpeakerphoneOn 强制外放降级（直读模式无法指定设备），幂等调用
        if (!engine.isLocalActive) applyForceSpeakerRouting()
        requestFocus()
        speaking = true
        updateSession(playing = true)
        startForegroundNotify()
        engine.speak(sentence.text, TtsEngine.utteranceId(idx, sentence.start))
        // V1.5.x 预取：播放当前句的同时后台合成下一句，消除句间合成空档（本地引擎消费；系统引擎忽略）
        sentences.getOrNull(idx + 1)?.let { engine.prepare(it.text) }
    }

    private val engineListener = object : TtsEngine.Listener {
        override fun onUtteranceStart(utteranceId: String) = Unit

        override fun onUtteranceDone(utteranceId: String) {
            if (!speaking) return // 被 stop() 取消，不续播
            scope.launch { speakFrom(sentenceIdx + 1) }
        }

        override fun onUtteranceError(utteranceId: String) {
            if (!speaking) return
            controller.updateState { it.copy(error = getString(R.string.tts_error_utterance)) }
            scope.launch { speakFrom(sentenceIdx + 1) }
        }
    }

    // ---------- 输出路由（V1.3 F-08：直读模式只能强制外放） ----------

    /**
     * 订阅「强制外放」设置：UI 切换即时生效（播放中打开立刻接管、关闭立刻还原）。
     * 直读模式（TextToSpeech.speak）无法指定任意设备，这里只做扬声器降级。
     */
    private fun observeForceSpeaker() {
        scope.launch {
            settingsRepository.listenSettings.collect { settings ->
                forceSpeaker = settings.forceSpeaker
                // 本地引擎消费 AudioTrack 设备级路由（首选设备 + 强制外放）；系统引擎忽略（由下方接管/还原负责）
                engine.setPreferredOutput(settings.preferredOutput)
                engine.setForceSpeaker(settings.forceSpeaker)
                if (settings.forceSpeaker) {
                    if (controller.state.value.isPlaying) applyForceSpeakerRouting()
                } else {
                    restoreSystemRouting()
                }
            }
        }
    }

    /**
     * 接管音频路由（幂等）：保存系统原状态后置 mode=MODE_NORMAL + isSpeakerphoneOn=true。
     * 多个 API 级别下 setSpeakerphoneOn 均为废弃 API，但仍是对 TTS 直读最有效的强制外放手段；
     * 缺少 MODIFY_AUDIO_SETTINGS 时系统静默忽略（本应用已在 manifest 声明）。
     * 失败只记日志，不影响朗读。
     */
    @Suppress("DEPRECATION")
    private fun applyForceSpeakerRouting() {
        if (!forceSpeaker) return
        if (savedSpeakerphoneOn != null) return // 已接管，避免覆盖保存的原状态
        // 通话/通信中不改路由：避免把来电或通话音频抢到外放（极端竞态保护）
        val currentMode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (currentMode == AudioManager.MODE_IN_CALL || currentMode == AudioManager.MODE_IN_COMMUNICATION) {
            android.util.Log.i(TAG, "当前处于通话模式($currentMode)，跳过强制外放接管")
            return
        }
        runCatching {
            savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
            savedAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = true
            android.util.Log.i(
                TAG,
                "强制外放已接管: saved speaker=${savedSpeakerphoneOn} mode=${savedAudioMode}" +
                    " → 读回 speaker=${audioManager.isSpeakerphoneOn} mode=${audioManager.mode}",
            )
        }.onFailure {
            savedSpeakerphoneOn = null
            savedAudioMode = null
            android.util.Log.w(TAG, "强制外放接管失败（忽略，跟随系统路由）", it)
        }
    }

    /**
     * 还原系统音频路由（与 [applyForceSpeakerRouting] 成对）：恢复保存的 speakerphone/mode，
     * 绝不把用户系统设置改成默认值。未接管时是空操作。
     *
     * 与接管侧对称的通话守卫：若当前处于通话/通信模式，**跳过还原**（避免把通话音频路由改回
     * NORMAL/听筒），保留 saved 状态，等下一次非通话的暂停/停止/销毁再还原。
     */
    @Suppress("DEPRECATION")
    private fun restoreSystemRouting() {
        val speaker = savedSpeakerphoneOn ?: return
        val currentMode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (currentMode == AudioManager.MODE_IN_CALL || currentMode == AudioManager.MODE_IN_COMMUNICATION) {
            android.util.Log.i(TAG, "当前处于通话模式($currentMode)，跳过音频路由还原（保留待还原状态）")
            return
        }
        val mode = savedAudioMode
        savedSpeakerphoneOn = null
        savedAudioMode = null
        runCatching {
            audioManager.isSpeakerphoneOn = speaker
            if (mode != null) audioManager.mode = mode
            android.util.Log.i(
                TAG,
                "强制外放已还原: 目标 speaker=$speaker mode=$mode" +
                    " → 读回 speaker=${audioManager.isSpeakerphoneOn} mode=${audioManager.mode}",
            )
        }.onFailure {
            android.util.Log.w(TAG, "还原音频路由失败（忽略）", it)
        }
    }

    // ---------- 音频焦点 / 拔耳机 ----------

    private fun requestFocus() {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        audioManager.requestAudioFocus(request)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // PRD V1 默认：抢占后保持暂停，用户手动恢复
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> pause()
        }
    }

    private fun registerNoisy() {
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
            }
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ---------- 媒体会话与通知 ----------

    private fun setupSession() {
        session = MediaSessionCompat(this, "YYXListen").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = nextSentence()
                override fun onSkipToPrevious() = prevSentence()
                override fun onFastForward() = nextChapter()
                override fun onRewind() = prevChapter()
            })
            isActive = true
        }
    }

    private fun updateSession(playing: Boolean) {
        val st = controller.state.value
        val duration = (sentences.lastOrNull()?.end ?: 1).toLong().coerceAtLeast(1)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    st.charOffset.coerceIn(0, duration.toInt()).toLong(),
                    if (playing) 1f else 0f,
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, st.bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, st.chapterTitle)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build(),
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TtsPlaybackController.CHANNEL_ID,
                getString(R.string.tts_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val st = controller.state.value
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            this, requestCode,
            Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TtsPlaybackController.CHANNEL_ID)
            .setSmallIcon(com.yyx.reader.R.drawable.ic_stat_book)
            .setContentTitle(st.bookTitle.ifEmpty { getString(R.string.app_name) })
            .setContentText(getString(R.string.tts_notification_text, st.chapterTitle, st.sentenceIdx + 1, st.sentenceCount.coerceAtLeast(1)))
            .setContentIntent(contentIntent)
            .setOngoing(st.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(
                if (st.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(R.string.tts_action_play_pause),
                serviceAction(TtsPlaybackController.ACTION_PLAY_PAUSE, 1),
            )
            .addAction(android.R.drawable.ic_media_next, getString(R.string.tts_action_next), serviceAction(TtsPlaybackController.ACTION_NEXT, 2))
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.tts_action_prev), serviceAction(TtsPlaybackController.ACTION_PREV, 3))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun startForegroundNotify() {
        val notification = buildNotification()
        if (!notificationStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    TtsPlaybackController.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(TtsPlaybackController.NOTIFICATION_ID, notification)
            }
            notificationStarted = true
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(TtsPlaybackController.NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() {
        if (notificationStarted) startForegroundNotify()
    }

    private companion object {
        const val TAG = "YYXTtsService"
    }
}
