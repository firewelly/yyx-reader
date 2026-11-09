package com.yyx.reader.feature.reader

import com.yyx.reader.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yyx.reader.core.datastore.ReaderSettings
import com.yyx.reader.core.datastore.ReaderSettingsRepository
import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.BookPosition
import com.yyx.reader.core.domain.model.Bookmark
import com.yyx.reader.core.domain.model.Chapter
import com.yyx.reader.core.domain.model.ProgressSource
import com.yyx.reader.core.domain.repository.BookRepository
import com.yyx.reader.core.domain.repository.BookmarkRepository
import com.yyx.reader.core.domain.repository.ChapterRepository
import com.yyx.reader.core.domain.repository.ProgressRepository
import com.yyx.reader.core.ebook.ChapterTextSource
import com.yyx.reader.service.tts.TtsPlaybackController
import com.yyx.reader.ui.theme.ReaderTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 阅读器 ViewModel：章节加载 + 统一进度写入（source=READ）+ 排版设置。
 * 页码状态由 UI 层持有（依赖分页器），VM 持有字符偏移（与听书共用坐标）。
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val progressRepository: ProgressRepository,
    private val textSource: ChapterTextSource,
    private val settingsRepository: ReaderSettingsRepository,
    private val ttsController: TtsPlaybackController,
    private val bookmarkRepository: BookmarkRepository,
) : ViewModel() {

    val bookId: Long = checkNotNull(savedStateHandle["bookId"])
    private val startChapterArg: Int? = (savedStateHandle["chapterIdx"] ?: -1).takeIf { it >= 0 }

    data class JumpRequest(
        val chapterIdx: Int,
        val offset: Int,
        val toLastPage: Boolean,
    )

    data class UiState(
        val book: Book? = null,
        val chapters: List<Chapter> = emptyList(),
        val chapterIdx: Int = 0,
        val text: String = "",
        val loading: Boolean = true,
        val error: Boolean = false,
        // 当前页首字符偏移（统一进度坐标）
        val positionOffset: Int = 0,
        val page: Int = 0,
        val pageCount: Int = 0,
        val percent: Float = 0f,
        // 设置
        val fontSizeSp: Int = 18,
        val lineSpacingDp: Int = 6,
        val theme: ReaderTheme = ReaderTheme.DAY,
        val showMenu: Boolean = false,
        val showToc: Boolean = false,
        // 章节跳转请求（UI 消费后调 clearJump）
        val jump: JumpRequest? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var charCounts: List<Pair<Int, Int>> = emptyList()
    private var totalChars: Long = 1

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _ui.update {
                    it.copy(
                        fontSizeSp = s.fontSizeSp,
                        lineSpacingDp = s.lineSpacingDp,
                        theme = ReaderTheme.of(s.theme),
                    )
                }
            }
        }
        viewModelScope.launch {
            val book = bookRepository.getBook(bookId)
            if (book == null) {
                _ui.update { it.copy(loading = false, error = true) }
                return@launch
            }
            val chapters = chapterRepository.chapters(bookId)
            charCounts = chapterRepository.charCounts(bookId)
            totalChars = charCounts.sumOf { it.second }.toLong().coerceAtLeast(1)

            val lastPosition = progressRepository.position(bookId)
            val maxIdx = (chapters.size - 1).coerceAtLeast(0)
            val chapterIdx = when {
                startChapterArg != null -> startChapterArg.coerceIn(0, maxIdx)
                lastPosition != null -> lastPosition.chapterIdx.coerceIn(0, maxIdx)
                else -> 0
            }
            val startOffset = if (startChapterArg == null) lastPosition?.charOffset ?: 0 else 0

            _ui.update { it.copy(book = book, chapters = chapters) }
            loadChapter(chapterIdx, startOffset)
        }
    }

    fun loadChapter(idx: Int, offset: Int = 0, toLastPage: Boolean = false) {
        val state = _ui.value
        val book = state.book ?: return
        val chapters = state.chapters
        if (chapters.isEmpty()) return
        val clamped = idx.coerceIn(0, chapters.size - 1)
        _ui.update { it.copy(chapterIdx = clamped, loading = true, jump = JumpRequest(clamped, offset, toLastPage)) }
        viewModelScope.launch {
            val text = textSource.load(book, chapters[clamped])
            val failed = text.startsWith(context.getString(R.string.reader_chapter_load_failed_title))
            _ui.update { it.copy(text = text, loading = false, error = failed) }
        }
    }

    /** UI 在分页器重建后消费跳转请求 */
    fun clearJump() {
        _ui.update { it.copy(jump = null) }
    }

    /** 页面协调唯一入口：UI 在分页器重建 / 页码变化 / 跳转消费后调用 */
    fun syncPage(page: Int, charOffset: Int) {
        val state = _ui.value
        // 百分比展示始终重算（相同页码时跳过数据库写入即可）
        _ui.update {
            it.copy(
                page = page,
                positionOffset = charOffset,
                percent = computePercent(it.chapterIdx, charOffset),
            )
        }
        val changed = state.page != page || state.positionOffset != charOffset
        if (charOffset >= 0 && changed) {
            viewModelScope.launch {
                progressRepository.update(BookPosition(bookId, state.chapterIdx, charOffset), ProgressSource.READ)
            }
        }
    }

    fun reportPageCount(count: Int) {
        _ui.update { it.copy(pageCount = count) }
    }

    fun goNextPage() {
        val state = _ui.value
        if (state.page < state.pageCount - 1) {
            _ui.update { it.copy(page = it.page + 1) }
        } else if (state.chapterIdx < state.chapters.size - 1) {
            loadChapter(state.chapterIdx + 1, 0)
        }
    }

    fun goPrevPage() {
        val state = _ui.value
        if (state.page > 0) {
            _ui.update { it.copy(page = it.page - 1) }
        } else if (state.chapterIdx > 0) {
            loadChapter(state.chapterIdx - 1, toLastPage = true)
        }
    }

    /** 全书百分比跳转（PRD F-04-06） */
    fun seekToFraction(fraction: Float) {
        if (charCounts.isEmpty()) return
        val target = (fraction.coerceIn(0f, 1f) * totalChars).toLong()
        var acc = 0L
        var idx = charCounts.size - 1
        var offset = 0
        for ((i, cc) in charCounts.withIndex()) {
            if (target < acc + cc.second) {
                idx = i
                offset = (target - acc).toInt()
                break
            }
            acc += cc.second
        }
        loadChapter(idx, offset)
    }

    fun toggleMenu() = _ui.update { it.copy(showMenu = !it.showMenu, showToc = false) }
    fun showToc(show: Boolean) = _ui.update { it.copy(showToc = show, showMenu = show) }

    fun setFontSize(size: Int) = viewModelScope.launch { settingsRepository.setFontSize(size) }
    fun setLineSpacing(dp: Int) = viewModelScope.launch { settingsRepository.setLineSpacing(dp) }
    fun setTheme(theme: ReaderTheme) = viewModelScope.launch { settingsRepository.setTheme(theme.name) }

    fun onVolumeKey(keyCode: Int): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
            goPrevPage(); true
        }
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
            goNextPage(); true
        }
        else -> false
    }

    // ---------- 听书（Phase C 接入，统一进度同步见架构文档 §5.4） ----------

    val listenState: StateFlow<TtsPlaybackController.ListenState> = ttsController.state

    /** 读→听：从当前页首字符所在句开始朗读（F-07-03） */
    fun startListening() {
        val state = _ui.value
        val book = state.book ?: return
        ttsController.startFromReader(book.id, state.chapterIdx, state.positionOffset)
    }

    fun pauseListening() = ttsController.pause()
    fun resumeListening() = ttsController.resume()
    fun stopListening() = ttsController.stop()
    fun listenNextSentence() = ttsController.nextSentence()
    fun listenPrevSentence() = ttsController.prevSentence()

    // ---------- 书签（PRD F-10：页首书签模型） ----------

    val bookmarks: StateFlow<List<Bookmark>> = bookmarkRepository.observeByBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeBookmark(id: Long) {
        viewModelScope.launch { bookmarkRepository.remove(id) }
    }

    private var cachedChapterText: String = ""

    fun isBookmarkedAt(chapterIdx: Int, offset: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(bookmarkRepository.findAt(bookId, chapterIdx, offset) != null)
        }
    }

    fun toggleBookmark(chapterIdx: Int, offset: Int, excerptSource: String) {
        viewModelScope.launch {
            val existing = bookmarkRepository.findAt(bookId, chapterIdx, offset)
            if (existing != null) {
                bookmarkRepository.remove(existing.id)
            } else {
                val excerpt = excerptSource
                    .substring(offset, (offset + 48).coerceAtMost(excerptSource.length))
                    .replace('\n', ' ')
                    .trim()
                    .takeIf { it.isNotBlank() }
                bookmarkRepository.add(bookId, chapterIdx, offset, excerpt)
            }
        }
    }

    suspend fun bookmarkPosition(bookmark: Bookmark): BookPosition =
        BookPosition(bookId, bookmark.chapterIdx, bookmark.charOffset)

    // ---------- 听书增强（PRD F-06/F-11） ----------

    /** 听书高亮跟随：只更新 UI 位置与百分比，不写 READ 进度（避免与 LISTEN 来源互踩） */
    fun followPage(page: Int, charOffset: Int) {
        _ui.update {
            it.copy(
                page = page,
                positionOffset = charOffset,
                percent = computePercent(it.chapterIdx, charOffset),
            )
        }
    }

    fun setListenRate(rate: Float) {
        viewModelScope.launch {
            settingsRepository.setTtsRate(rate)
            ttsController.setRate(rate)
        }
    }

    fun sleepTimer(minutes: Int) = ttsController.sleepTimer(minutes)
    fun cancelSleepTimer() = ttsController.cancelSleepTimer()
    fun stopAtChapterEnd(enabled: Boolean) = ttsController.stopAtChapterEnd(enabled)

    private fun computePercent(chapterIdx: Int, offset: Int): Float {
        if (charCounts.isEmpty()) return 0f
        val before = charCounts.filter { it.first < chapterIdx }.sumOf { it.second }
        val inChapter = charCounts.firstOrNull { it.first == chapterIdx }?.second ?: 1
        val chapterFraction = (offset.toFloat() / inChapter.coerceAtLeast(1)).coerceIn(0f, 1f)
        return ((before + inChapter * chapterFraction) / totalChars).toFloat().coerceIn(0f, 1f)
    }
}
