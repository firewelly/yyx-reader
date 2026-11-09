package com.yyx.reader.feature.reader

import android.Manifest
import android.graphics.Paint
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yyx.reader.R
import com.yyx.reader.core.audio.AudioOutput
import com.yyx.reader.core.audio.OutputDeviceRepository
import com.yyx.reader.core.audio.deviceRouteKey
import com.yyx.reader.core.audio.matchesRouteKey
import com.yyx.reader.core.audio.outputSummary
import com.yyx.reader.core.audio.supportsForceSpeaker
import com.yyx.reader.core.datastore.ListenSettings
import com.yyx.reader.core.datastore.ReaderSettingsRepository
import com.yyx.reader.core.tts.TtsEngineRouter
import com.yyx.reader.ui.theme.ReaderTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 阅读器（PRD F-04）：
 * - 点击分区：左 1/3 上一页，右 1/3 下一页，中间呼出菜单
 * - 菜单：进度滑杆（全书）、章节切换、字号/行距、主题、目录
 * - 进度以「章内字符偏移」写入统一进度（听书共用，架构文档 §5.4）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val listen by viewModel.listenState.collectAsStateWithLifecycle()
    val view = LocalView.current
    val density = LocalDensity.current
    var showExitDialog by remember { mutableStateOf(false) }
    var hasBookmark by remember { mutableStateOf(false) }

    // 通知权限（听书前台服务通知，Android 13+）
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 屏幕常亮（F-04-04）
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 音量键翻页（F-04-08）
    DisposableEffect(Unit) {
        VolumeKeyHub.handler = viewModel::onVolumeKey
        onDispose { VolumeKeyHub.handler = null }
    }

    // 当前页首是否已有书签（页首书签模型）
    LaunchedEffect(ui.chapterIdx, ui.positionOffset) {
        viewModel.isBookmarkedAt(ui.chapterIdx, ui.positionOffset) { hasBookmark = it }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ui.theme.bg)
            .systemBarsPadding(),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt()
        val heightPx = with(density) { maxHeight.toPx() }.toInt()
        val hPadPx = with(density) { 20.dp.toPx() }.toInt()
        val vPadPx = with(density) { 32.dp.toPx() }.toInt()
        val lineSpacingPx = with(density) { ui.lineSpacingDp.dp.toPx() }.toInt()

        // 听书高亮：当前句经 BackgroundColorSpan 注入（F-06-09）
        val displayText: CharSequence = remember(
            ui.text, ui.chapterIdx, ui.theme,
            listen.active, listen.bookId, listen.chapterIdx, listen.sentenceStart, listen.sentenceEnd,
        ) {
            val sameBook = listen.active && listen.bookId == ui.book?.id
            if (sameBook && listen.chapterIdx == ui.chapterIdx &&
                listen.sentenceStart < listen.sentenceEnd && listen.sentenceEnd <= ui.text.length
            ) {
                SpannableString(ui.text).apply {
                    setSpan(
                        BackgroundColorSpan(ui.theme.highlight.toArgb()),
                        listen.sentenceStart,
                        listen.sentenceEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            } else {
                ui.text
            }
        }

        val paginator = remember(displayText, ui.fontSizeSp, lineSpacingPx, ui.theme, widthPx, heightPx) {
            ChapterPaginator.build(
                text = displayText,
                textSizePx = with(density) { ui.fontSizeSp.sp.toPx() },
                lineSpacingPx = lineSpacingPx,
                colorArgb = ui.theme.text.toArgb(),
                width = widthPx - 2 * hPadPx,
                height = heightPx - 2 * vPadPx,
            )
        }

        // 分页器重建 / 章节加载后：消费跳转或恢复位置（听书进行中不写 READ 进度）
        LaunchedEffect(paginator) {
            if (paginator.pages.isEmpty()) return@LaunchedEffect
            viewModel.reportPageCount(paginator.pages.size)
            val jump = viewModel.ui.value.jump
            val targetPage = when {
                jump?.toLastPage == true -> paginator.pages.size - 1
                jump != null -> paginator.pageOf(jump.offset.coerceAtLeast(0))
                else -> paginator.pageOf(viewModel.ui.value.positionOffset)
            }
            if (jump != null) viewModel.clearJump()
            val ls = viewModel.listenState.value
            if (ls.active && ls.bookId == viewModel.ui.value.book?.id) {
                viewModel.followPage(targetPage, paginator.pages[targetPage].first)
            } else {
                viewModel.syncPage(targetPage, paginator.pages[targetPage].first)
            }
        }

        // 听书高亮跟随（F-06-09）：播放中自动跟随当前句
        LaunchedEffect(listen.sentenceIdx, listen.isPlaying) {
            if (listen.active && listen.isPlaying && listen.bookId == ui.book?.id) {
                if (listen.chapterIdx != ui.chapterIdx) {
                    viewModel.loadChapter(listen.chapterIdx, listen.sentenceStart)
                } else if (paginator.pages.isNotEmpty()) {
                    val p = paginator.pageOf(listen.sentenceStart)
                    viewModel.followPage(p, paginator.pages[p].first)
                }
            }
        }

        // VM 页码变化 → 同步字符偏移并落盘进度
        LaunchedEffect(ui.page) {
            if (paginator.pages.isNotEmpty() && ui.page in paginator.pages.indices) {
                viewModel.syncPage(ui.page, paginator.pages[ui.page].first)
            }
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ui.theme.text)
            }
            ui.text.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.reader_empty_chapter), color = ui.theme.text.copy(alpha = 0.6f))
            }
            else -> {
                // 正文绘制
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        canvas.save()
                        canvas.translate(hPadPx.toFloat(), vPadPx.toFloat())
                        paginator.draw(canvas.nativeCanvas, ui.page)
                        canvas.restore()
                    }
                }
                // 点击分区
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                when {
                                    // 翻页触控区（产品规格）：左右各 1/4 屏宽翻页，中间 1/2 为菜单开关
                                    pos.x < size.width / 4f -> viewModel.goPrevPage()
                                    pos.x > size.width * 3f / 4f -> viewModel.goNextPage()
                                    else -> viewModel.toggleMenu()
                                }
                            }
                        },
                )
            }
        }

        // 页眉页脚信息（F-04-07）
        if (!ui.loading && ui.text.isNotEmpty() && !ui.showMenu) {
            Text(
                text = ui.chapters.getOrNull(ui.chapterIdx)?.title ?: "",
                color = ui.theme.text.copy(alpha = 0.45f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 8.dp, end = 60.dp),
            )
            Text(
                text = stringResource(R.string.reader_page_indicator, ui.page + 1, ui.pageCount.coerceAtLeast(1), (ui.percent * 100).toInt()),
                color = ui.theme.text.copy(alpha = 0.45f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            )
        }

        // 顶部菜单
        AnimatedVisibility(
            visible = ui.showMenu,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showExitDialog = true }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                }
                IconButton(onClick = { viewModel.showToc(true) }) {
                    Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.reader_toc))
                }
                Text(
                    text = ui.chapters.getOrNull(ui.chapterIdx)?.title ?: "",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = {
                    viewModel.toggleBookmark(ui.chapterIdx, ui.positionOffset, ui.text)
                }) {
                    Icon(
                        if (hasBookmark) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        stringResource(R.string.reader_bookmarks),
                        tint = if (hasBookmark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.startListening()
                }) {
                    Icon(Icons.Filled.Headphones, stringResource(R.string.reader_listen), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 听书控制条 + 底部设置面板
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            if (listen.active) {
                ListenBar(viewModel = viewModel)
            }
            AnimatedVisibility(
                visible = ui.showMenu,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BottomPanel(viewModel = viewModel)
            }
        }

        // 目录 / 书签面板
        if (ui.showToc) {
            ModalBottomSheet(onDismissRequest = { viewModel.showToc(false) }) {
                var tocTab by remember { mutableStateOf(0) }
                val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterChip(
                        selected = tocTab == 0,
                        onClick = { tocTab = 0 },
                        label = { Text(stringResource(R.string.reader_toc_count, ui.chapters.size)) },
                    )
                    FilterChip(
                        selected = tocTab == 1,
                        onClick = { tocTab = 1 },
                        label = { Text(stringResource(R.string.reader_bookmarks_count, bookmarks.size)) },
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                    if (tocTab == 0) {
                        items(ui.chapters, key = { it.id }) { chapter ->
                            Text(
                                text = chapter.title,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (chapter.idx == ui.chapterIdx) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.loadChapter(chapter.idx, 0)
                                        viewModel.showToc(false)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
                    } else {
                        if (bookmarks.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.reader_no_bookmarks),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(20.dp),
                                )
                            }
                        }
                        items(bookmarks, key = { it.id }) { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.loadChapter(bookmark.chapterIdx, bookmark.charOffset)
                                        viewModel.showToc(false)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bookmark.excerpt ?: stringResource(R.string.reader_bookmarks),
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = ui.chapters.getOrNull(bookmark.chapterIdx)?.title
                                            ?: stringResource(R.string.reader_chapter_index, bookmark.chapterIdx + 1),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { viewModel.removeBookmark(bookmark.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        stringResource(R.string.reader_delete_bookmark),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(stringResource(R.string.reader_exit_title)) },
                text = { Text(stringResource(R.string.reader_exit_message)) },
                confirmButton = {
                    Button(onClick = onBack) { Text(stringResource(R.string.reader_exit_button)) }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) { Text(stringResource(R.string.reader_continue_button)) }
                },
            )
        }
    }
}

/** 听书控制条（F-06-07）：当前句展示 + 句级/播放控制 */
@Composable
private fun ListenBar(viewModel: ReaderViewModel) {
    val listen by viewModel.listenState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.97f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        listen.error?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text = listen.chapterTitle,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listen.currentSentence,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::listenPrevSentence) {
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.reader_prev_sentence), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            IconButton(onClick = { if (listen.isPlaying) viewModel.pauseListening() else viewModel.resumeListening() }) {
                Icon(
                    if (listen.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (listen.isPlaying) stringResource(R.string.reader_pause) else stringResource(R.string.reader_resume),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = viewModel::listenNextSentence) {
                Icon(Icons.Filled.SkipNext, stringResource(R.string.reader_next_sentence), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            // 定时停止（F-06-08）
            Box {
                var timerMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { timerMenu = true }) {
                    Icon(
                        Icons.Filled.Schedule,
                        stringResource(R.string.reader_sleep_timer),
                        tint = if (listen.sleepTimerEndsAt != null || listen.stopAtChapterEnd) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
                DropdownMenu(expanded = timerMenu, onDismissRequest = { timerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_cancel_timer)) },
                        onClick = { viewModel.cancelSleepTimer(); timerMenu = false },
                    )
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_stop_in_minutes, minutes)) },
                            onClick = { viewModel.sleepTimer(minutes); timerMenu = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_stop_at_chapter_end)) },
                        onClick = { viewModel.stopAtChapterEnd(true); timerMenu = false },
                    )
                }
            }
            IconButton(onClick = viewModel::stopListening) {
                Icon(Icons.Filled.Close, stringResource(R.string.reader_stop_listening), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        // 第二行：语速循环 + 定时状态
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ratePresets = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
            TextButton(onClick = {
                val next = ratePresets[(ratePresets.indexOf(listen.rate) + 1).mod(ratePresets.size)]
                viewModel.setListenRate(next)
            }) {
                Text(
                    text = if (listen.rate == listen.rate.toInt().toFloat()) "${listen.rate.toInt()}x" else "${listen.rate}x",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (listen.sleepTimerEndsAt != null) {
                Text(
                    stringResource(R.string.reader_timer_active),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (listen.stopAtChapterEnd) {
                Text(
                    stringResource(R.string.reader_stop_at_chapter_end),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        // 输出设备 / 强制外放（V1.3 F-08 降级实现）
        AudioOutputSection()
    }
}

/**
 * 听书输出路由（V1.3 F-08，**诚实的降级实现**）：
 * - 只读展示系统当前可用输出设备（插拔自动刷新，[OutputDeviceRepository]）；
 * - 「强制外放」开关持久化到 [ListenSettings.forceSpeaker]，播报时由 TtsPlaybackService 接管 speakerphone；
 * - 直读模式（TextToSpeech.speak）无法把声音定向到任意设备，有线/蓝牙只能跟随系统；
 * - 本地引擎（V2 精确音频路由）：可从在线设备列表直接选择输出目标（AudioTrack.setPreferredDevice），
 *   选择器持久化到 [ListenSettings.preferredOutput]，设备拔出/不在线自动回退跟随系统。
 *
 * 只读取设置/设备仓库（Hilt EntryPoint），不扩散改动 ReaderViewModel。
 */
@Composable
private fun AudioOutputSection() {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReaderAudioEntryPoint::class.java,
        )
    }
    val settingsRepository = remember(entryPoint) { entryPoint.settingsRepository() }
    val listenSettings by settingsRepository.listenSettings
        .collectAsStateWithLifecycle(initialValue = ListenSettings())
    val outputs by remember(entryPoint) { entryPoint.outputDeviceRepository().observeOutputs() }
        .collectAsStateWithLifecycle(initialValue = emptyList<AudioOutput>())
    // V2 精确音频路由：逐设备列表（不去重，含 id/address）
    val devices by remember(entryPoint) { entryPoint.outputDeviceRepository().observeDevices() }
        .collectAsStateWithLifecycle(initialValue = emptyList<AudioOutput>())
    val isLocalEngine = listenSettings.enginePackage == TtsEngineRouter.LOCAL_ENGINE_ID
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.reader_output_device, outputsSummary(outputs)),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (isLocalEngine) {
            // 本地引擎（V2）：AudioTrack.setPreferredDevice 定向任意在线设备
            Text(
                text = stringResource(R.string.reader_output_to),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            val selected = listenSettings.preferredOutput
            OutputRouteRow(
                label = stringResource(R.string.reader_follow_system),
                selected = selected == null,
                onSelect = { scope.launch { settingsRepository.setPreferredOutput(null) } },
            )
            devices.take(OUTPUT_ROUTE_MAX_ROWS).forEach { device ->
                val key = deviceRouteKey(device.type, device.address)
                OutputRouteRow(
                    label = stringResource(device.typeLabelRes) + if (device.name.isNotBlank()) " · ${device.name}" else "",
                    selected = selected == key,
                    onSelect = { scope.launch { settingsRepository.setPreferredOutput(key) } },
                )
            }
            if (devices.size > OUTPUT_ROUTE_MAX_ROWS) {
                Text(
                    text = stringResource(R.string.reader_more_devices_hidden, devices.size - OUTPUT_ROUTE_MAX_ROWS),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
            if (selected != null && devices.none { matchesRouteKey(it.type, it.address, selected) }) {
                Text(
                    text = stringResource(R.string.reader_selected_offline),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            Text(
                text = stringResource(R.string.reader_local_route_hint),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.reader_force_speaker),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Switch(
                    checked = listenSettings.forceSpeaker,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepository.setForceSpeaker(enabled) }
                    },
                    modifier = Modifier.padding(start = 6.dp),
                )
                if (!supportsForceSpeaker(outputs)) {
                    Text(
                        text = stringResource(R.string.reader_no_speaker),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.reader_system_route_hint),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
            )
        }
    }
}

/** 输出路由单选行（V2 精确音频路由） */
@Composable
private fun OutputRouteRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private const val OUTPUT_ROUTE_MAX_ROWS = 6

/**
 * 听书面板的 Hilt 入口：读取听书设置与输出设备仓库。
 * 面板仅做展示/开关，为避免扩散写入域到 ReaderViewModel 而单独声明入口。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReaderAudioEntryPoint {
    fun settingsRepository(): ReaderSettingsRepository
    fun outputDeviceRepository(): OutputDeviceRepository
}

@Composable
private fun BottomPanel(viewModel: ReaderViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var sliderValue by remember(ui.showMenu) { mutableFloatStateOf(ui.percent) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // 全书进度
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${(sliderValue * 100).toInt()}%",
                fontSize = 12.sp,
                modifier = Modifier.width(44.dp),
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { viewModel.seekToFraction(sliderValue) },
                modifier = Modifier.weight(1f),
            )
        }
        // 章节切换
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FilledTonalButton(onClick = {
                val idx = ui.chapterIdx
                if (idx > 0) viewModel.loadChapter(idx - 1, toLastPage = true)
            }, enabled = ui.chapterIdx > 0) { Text(stringResource(R.string.reader_prev_chapter)) }
            Text(
                text = "${ui.chapterIdx + 1}/${ui.chapters.size}",
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            FilledTonalButton(onClick = {
                val idx = ui.chapterIdx
                if (idx < ui.chapters.size - 1) viewModel.loadChapter(idx + 1, 0)
            }, enabled = ui.chapterIdx < ui.chapters.size - 1) { Text(stringResource(R.string.reader_next_chapter)) }
        }
        Spacer(Modifier.height(8.dp))
        // 字号 / 行距 / 主题
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            SettingStepper("A-", stringResource(R.string.reader_font_size, ui.fontSizeSp), "A+") {
                viewModel.setFontSize(if (it) ui.fontSizeSp + 1 else ui.fontSizeSp - 1)
            }
            SettingStepper("-", stringResource(R.string.reader_line_spacing, ui.lineSpacingDp), "+") {
                viewModel.setLineSpacing(if (it) ui.lineSpacingDp + 2 else ui.lineSpacingDp - 2)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReaderTheme.entries.forEach { theme ->
                TextButton(
                    onClick = { viewModel.setTheme(theme) },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        containerColor = if (ui.theme == theme) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        },
                    ),
                ) { Text(stringResource(theme.labelRes), fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingStepper(minusLabel: String, label: String, plusLabel: String, onStep: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { onStep(false) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
            Text(minusLabel)
        }
        Text(
            label,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(76.dp)
                .padding(horizontal = 4.dp),
        )
        FilledTonalButton(onClick = { onStep(true) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
            Text(plusLabel)
        }
    }
}

/** 输出设备摘要（多语言）：空列表用 audio_none_detected 兜底 */
@Composable
private fun outputsSummary(outputs: List<AudioOutput>): String {
    if (outputs.isEmpty()) return stringResource(R.string.audio_none_detected)
    // 先在组合上下文里解析全部标签，再交给纯函数拼接（lambda 内不能调用 @Composable）
    val labels = outputs.map { stringResource(it.typeLabelRes) }
    return outputs.mapIndexed { i, d ->
        if (d.count > 1) "${labels[i]}×${d.count}" else labels[i]
    }.joinToString(" · ")
}
