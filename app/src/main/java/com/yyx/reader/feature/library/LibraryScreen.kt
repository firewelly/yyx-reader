package com.yyx.reader.feature.library

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.yyx.reader.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yyx.reader.core.domain.model.ScanFolder
import com.yyx.reader.core.domain.model.ShelfBook
import com.yyx.reader.core.domain.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 书架首页（PRD F-02 + F-13 标签筛选与批量打标） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showScanSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTagSheet by remember { mutableStateOf(false) }
    var tagToDelete by remember { mutableStateOf<Tag?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addFolder)
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (ui.selection.isEmpty()) {
                LibraryTopBar(
                    searching = ui.searching,
                    query = ui.query,
                    onQueryChange = viewModel::setQuery,
                    onToggleSearch = { viewModel.setSearching(!ui.searching) },
                    sort = ui.sort,
                    onSortChange = viewModel::setSort,
                    onScan = { showScanSheet = true },
                    onSettings = onOpenSettings,
                )
            } else {
                SelectionTopBar(
                    count = ui.selection.size,
                    onClose = viewModel::clearSelection,
                    onTag = { showTagSheet = true },
                    onDelete = { showDeleteDialog = true },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // F-13：书架顶部标签筛选行（有标签且非多选态时出现）
            if (ui.tags.isNotEmpty() && ui.selection.isEmpty()) {
                TagFilterRow(
                    tags = ui.tags,
                    filterTagId = ui.filterTagId,
                    onSelect = viewModel::setFilterTag,
                    onLongPress = { tagToDelete = it },
                )
            }
            when {
                ui.books.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ui.books, key = { it.book.id }) { shelf ->
                        BookCard(
                            shelf = shelf,
                            tags = ui.tagMap[shelf.book.id].orEmpty(),
                            selectionMode = ui.selection.isNotEmpty(),
                            selected = shelf.book.id in ui.selection,
                            onClick = {
                                if (ui.selection.isEmpty()) onOpenBook(shelf.book.id)
                                else viewModel.toggleSelect(shelf.book.id)
                            },
                            onLongClick = { viewModel.toggleSelect(shelf.book.id) },
                        )
                    }
                }
                ui.filterTagId != null || ui.query.isNotBlank() -> NoMatchState()
                else -> EmptyState(
                    modifier = Modifier.fillMaxSize(),
                    onAddFolder = { folderPicker.launch(null) },
                    onScan = { showScanSheet = true },
                )
            }
        }
    }

    if (showScanSheet) {
        ScanSheet(
            ui = ui,
            onDismiss = { showScanSheet = false },
            onAddFolder = { folderPicker.launch(null) },
            onRemoveFolder = viewModel::removeFolder,
            onStartScan = viewModel::startScan,
            onCancelScan = viewModel::cancelScan,
        )
    }

    // F-13：多选后批量打标
    if (showTagSheet) {
        TagSheet(
            bookCount = ui.selection.size,
            tags = ui.tags,
            initialChecked = commonTagIds(ui.selection, ui.tagMap),
            onDismiss = { showTagSheet = false },
            onCreateTag = viewModel::createTag,
            onApply = { checked ->
                viewModel.applyTags(ui.selection, checked)
                showTagSheet = false
            },
        )
    }

    // F-13：长按标签 chip 删除确认
    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            title = { Text(stringResource(R.string.library_delete_tag_title)) },
            text = { Text(stringResource(R.string.library_delete_tag_message, tag.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTag(tag)
                    tagToDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.library_delete_books_title, ui.selection.size)) },
            text = { Text(stringResource(R.string.library_delete_books_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected(true)
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.library_delete_sources), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.deleteSelected(false)
                        showDeleteDialog = false
                    }) { Text(stringResource(R.string.library_remove_only)) }
                    TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    sort: Sort,
    onSortChange: (Sort) -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    androidx.compose.material3.CenterAlignedTopAppBar(
        title = {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.library_search_hint), fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(stringResource(R.string.library_title))
            }
        },
        navigationIcon = {
            if (searching) {
                IconButton(onClick = onToggleSearch) { Icon(Icons.Filled.Close, stringResource(R.string.library_close_search)) }
            } else {
                Icon(
                    Icons.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        actions = {
            if (!searching) {
                IconButton(onClick = onToggleSearch) { Icon(Icons.Filled.Search, stringResource(R.string.common_search)) }
                IconButton(onClick = { sortMenuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.common_sort)) }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    Sort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sortLabelRes(s)) + if (s == sort) " ✓" else "") },
                            onClick = { onSortChange(s); sortMenuOpen = false },
                        )
                    }
                }
                IconButton(onClick = onScan) { Icon(Icons.Filled.Refresh, stringResource(R.string.library_scan_import)) }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.common_settings)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onTag: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.library_selected_count, count)) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.library_deselect)) } },
        actions = {
            // F-13：批量打标入口
            IconButton(onClick = onTag) { Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.library_tags)) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error) }
        },
    )
}

/** 标签筛选行：横向 chips，「全部」+ 各标签；长按标签删除 */
@Composable
private fun TagFilterRow(
    tags: List<Tag>,
    filterTagId: Long?,
    onSelect: (Long?) -> Unit,
    onLongPress: (Tag) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            TagChip(text = stringResource(R.string.library_filter_all), selected = filterTagId == null, onClick = { onSelect(null) })
        }
        lazyItems(tags, key = { it.id }) { tag ->
            TagChip(
                text = tag.name,
                selected = filterTagId == tag.id,
                onClick = { onSelect(tag.id) },
                onLongClick = { onLongPress(tag) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 批量打标底部面板：标签复选（已勾选=所选书籍共同标签）+ 新建标签输入框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagSheet(
    bookCount: Int,
    tags: List<Tag>,
    initialChecked: Set<Long>,
    onDismiss: () -> Unit,
    onCreateTag: (String, (Long) -> Unit, (String) -> Unit) -> Unit,
    onApply: (Set<Long>) -> Unit,
) {
    var checked by remember { mutableStateOf(initialChecked) }
    var newName by remember { mutableStateOf("") }
    // 新建失败（空名/重名）就地提示：Scaffold 的 snackbar 会被 ModalBottomSheet 遮住
    var createError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.library_tag_panel_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.library_tag_panel_hint, bookCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            if (tags.isEmpty()) {
                Text(
                    stringResource(R.string.library_no_tags),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    tags.forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checked = if (tag.id in checked) checked - tag.id else checked + tag.id
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = tag.id in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + tag.id else checked - tag.id
                                },
                            )
                            Text(tag.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.library_new_tag)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        createError = null
                        onCreateTag(
                            newName,
                            { id ->
                                // 新建成功后自动勾选
                                checked = checked + id
                                newName = ""
                            },
                            { reason -> createError = reason },
                        )
                    },
                    enabled = newName.isNotBlank(),
                ) { Text(stringResource(R.string.library_create)) }
            }

            createError?.let { reason ->
                Text(
                    text = reason,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Button(
                onClick = { onApply(checked) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 20.dp),
            ) { Text(stringResource(R.string.library_apply_to, bookCount)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    shelf: ShelfBook,
    tags: List<Tag>,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val book = shelf.book
    val coverBitmap by produceState<android.graphics.Bitmap?>(null, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            book.coverPath?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
        }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(coverColor(book.id)),
        ) {
            coverBitmap?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book.title.take(1),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                }
            }
            shelf.percent?.let { percent ->
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { percent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                    Text(
                        text = stringResource(R.string.library_read_percent, (percent * 100).toInt()),
                        color = Color.White,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Text(
            text = book.title,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            text = book.author ?: book.format.name.lowercase(),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // F-13：所属标签（最多 2 个 + “+n”）
        tagSummary(tags)?.let { summary ->
            Text(
                text = summary,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

private fun coverColor(id: Long): Color {
    val palette = listOf(
        Color(0xFF5C7CFA), Color(0xFF38B2AC), Color(0xFFED8936),
        Color(0xFF9F7AEA), Color(0xFFFC8181), Color(0xFF4FD1C5),
        Color(0xFF667EEA), Color(0xFFB7791F),
    )
    return palette[(id % palette.size).toInt()]
}

/** 筛选/搜索无结果提示 */
@Composable
private fun NoMatchState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.library_no_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAddFolder: () -> Unit, onScan: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            stringResource(R.string.library_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
        )
        Button(onClick = onAddFolder, modifier = Modifier.padding(top = 20.dp)) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.library_choose_folder))
        }
        FilledTonalButton(onClick = onScan, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.library_open_scan_panel))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanSheet(
    ui: LibraryViewModel.UiState,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (ScanFolder) -> Unit,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.library_scan_section), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.library_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))

            ui.folders.forEach { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(folder.displayName, fontSize = 14.sp)
                        folder.lastScanAt?.let {
                            Text(
                                stringResource(R.string.library_last_scan, java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(it)),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onRemoveFolder(folder) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.library_remove_folder), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            OutlinedButton(onClick = onAddFolder, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.library_add_folder))
            }

            Spacer(Modifier.height(16.dp))
            if (ui.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        ui.scanProgress,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onCancelScan) { Text(stringResource(R.string.common_cancel)) }
                }
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 8.dp),
                )
            } else {
                Button(onClick = onStartScan, enabled = ui.folders.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.library_start_scan))
                }
            }
            ui.scanReport?.let { report ->
                Text(
                    text = scanSummaryText(report) + (if (report.errors.isNotEmpty()) "\n" + report.errors.take(3).joinToString("\n") else ""),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
            } ?: Spacer(Modifier.height(24.dp))
        }
    }
}

/** 排序方式中文/英文标签映射（多语言：资源 ID 由界面层解析） */
private fun sortLabelRes(sort: Sort): Int = when (sort) {
    Sort.RECENT -> R.string.library_sort_recent
    Sort.ADDED -> R.string.library_sort_added
    Sort.TITLE -> R.string.library_sort_title
    Sort.AUTHOR -> R.string.library_sort_author
}

/** 扫描结果摘要（多语言） */
@Composable
private fun scanSummaryText(report: com.yyx.reader.core.domain.model.ScanReport): String =
    stringResource(R.string.scan_summary_added, report.added, report.updated, report.skipped, report.failed)
