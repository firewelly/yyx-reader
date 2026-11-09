package com.yyx.reader.service.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 听书播放控制器（单例状态总线）：
 * - UI（阅读器/听书面板）读写 [state]
 * - 命令路由到 [TtsPlaybackService]（executor 模式，架构文档 §5.6.4）
 */
@Singleton
class TtsPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class ListenState(
        val active: Boolean = false,
        val bookId: Long = 0,
        val bookTitle: String = "",
        val chapterIdx: Int = 0,
        val chapterTitle: String = "",
        val sentenceIdx: Int = 0,
        val sentenceCount: Int = 0,
        val currentSentence: String = "",
        val charOffset: Int = 0,
        /** 当前句在章内的字符区间（阅读器高亮跟随用，架构文档 §5.6） */
        val sentenceStart: Int = 0,
        val sentenceEnd: Int = 0,
        val isPlaying: Boolean = false,
        val engineReady: Boolean = false,
        val rate: Float = 1.0f,
        /** 定时停止到期时间戳（毫秒），null = 未启用 */
        val sleepTimerEndsAt: Long? = null,
        /** 本章结束即停 */
        val stopAtChapterEnd: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    internal var executor: Executor? = null

    internal interface Executor {
        fun start(bookId: Long, chapterIdx: Int, charOffset: Int)
        fun resume()
        fun pause()
        fun nextSentence()
        fun prevSentence()
        fun nextChapter()
        fun prevChapter()
        fun stopPlayback()
        fun setRate(rate: Float)
        /** 定时停止：minutes 分钟后暂停 */
        fun sleepTimer(minutes: Int)
        fun cancelSleepTimer()
        /** 本章结束即停 */
        fun stopAtChapterEnd(enabled: Boolean)
    }

    // ---------- UI 命令入口 ----------

    fun startFromReader(bookId: Long, chapterIdx: Int, charOffset: Int) {
        val intent = Intent(context, TtsPlaybackService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_BOOK_ID, bookId)
            putExtra(EXTRA_CHAPTER, chapterIdx)
            putExtra(EXTRA_OFFSET, charOffset)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun resume() = executor?.resume()
    fun pause() = executor?.pause()
    fun nextSentence() = executor?.nextSentence()
    fun prevSentence() = executor?.prevSentence()
    fun nextChapter() = executor?.nextChapter()
    fun prevChapter() = executor?.prevChapter()
    fun stop() = executor?.stopPlayback()
    fun setRate(rate: Float) = executor?.setRate(rate)
    fun sleepTimer(minutes: Int) = executor?.sleepTimer(minutes)
    fun cancelSleepTimer() = executor?.cancelSleepTimer()
    fun stopAtChapterEnd(enabled: Boolean) = executor?.stopAtChapterEnd(enabled)

    // ---------- 状态更新（服务内部调用） ----------

    internal fun updateState(transform: (ListenState) -> ListenState) {
        _state.value = transform(_state.value)
    }

    internal fun reset() {
        _state.value = ListenState()
    }

    companion object {
        const val ACTION_START = "com.yyx.reader.tts.START"
        const val ACTION_PLAY_PAUSE = "com.yyx.reader.tts.PLAY_PAUSE"
        const val ACTION_NEXT = "com.yyx.reader.tts.NEXT"
        const val ACTION_PREV = "com.yyx.reader.tts.PREV"
        const val ACTION_NEXT_CH = "com.yyx.reader.tts.NEXT_CH"
        const val ACTION_PREV_CH = "com.yyx.reader.tts.PREV_CH"
        const val ACTION_STOP = "com.yyx.reader.tts.STOP"

        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_CHAPTER = "chapterIdx"
        const val EXTRA_OFFSET = "charOffset"

        const val CHANNEL_ID = "tts_playback"
        const val NOTIFICATION_ID = 1001
    }
}
