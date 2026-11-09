package com.yyx.reader.feature.bookdetail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.yyx.reader.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.BookFormat
import com.yyx.reader.core.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 书籍详情：元数据 / 目录 / 编辑 / 删除 / 重新解析（PRD F-03） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onRead: (Long, Int?) -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCharsetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: stringResource(R.string.detail_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.detail_edit)) }
                    if (book?.format == BookFormat.TXT) {
                        TextButton(onClick = { showCharsetDialog = true }) { Text(stringResource(R.string.detail_encoding), fontSize = 13.sp) }
                    }
                    TextButton(onClick = { viewModel.reparse() }) { Text(stringResource(R.string.detail_reparse), fontSize = 13.sp) }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { padding ->
        val currentBook = book
        if (currentBook == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { BookHeader(book = currentBook, progressPercent = progress?.percent) }
            item {
                Button(
                    onClick = { onRead(currentBook.id, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (progress == null) stringResource(R.string.detail_start_reading) else stringResource(R.string.detail_continue_reading))
                }
            }
            item {
                Text(
                    stringResource(R.string.detail_toc_count, chapters.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(chapters, key = { it.id }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    isCurrent = progress?.position?.chapterIdx == chapter.idx,
                    onClick = { onRead(currentBook.id, chapter.idx) },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (showEditDialog && book != null) {
        EditMetaDialog(book = book!!, onDismiss = { showEditDialog = false }, onSave = viewModel::updateMetadata)
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.detail_delete_book, book?.title.orEmpty())) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(true, onBack)
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.library_delete_sources), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.delete(false, onBack)
                        showDeleteDialog = false
                    }) { Text(stringResource(R.string.library_remove_only)) }
                    TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }

    if (showCharsetDialog) {
        CharsetDialog(
            current = book?.charset,
            onDismiss = { showCharsetDialog = false },
            onSelect = {
                viewModel.setCharset(it)
                showCharsetDialog = false
            },
        )
    }
}

@Composable
private fun BookHeader(book: Book, progressPercent: Float?) {
    val coverBitmap by produceState<android.graphics.Bitmap?>(null, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            book.coverPath?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Box(
            modifier = Modifier
                .width(110.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF5C7CFA)),
        ) {
            coverBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(book.title.take(1), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                book.author ?: stringResource(R.string.common_author_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(book.format.name, fontSize = 11.sp) })
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.detail_chapter_count, book.chapterCount), fontSize = 11.sp) })
                AssistChip(onClick = {}, label = { Text(formatWordCount(book.wordCount), fontSize = 11.sp) })
            }
            progressPercent?.let {
                Text(
                    stringResource(R.string.library_read_percent, (it * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
    book.summary?.takeIf { it.isNotBlank() }?.let { summary ->
        var expanded by remember { mutableStateOf(false) }
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clickable { expanded = !expanded },
        )
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.title,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.detail_char_count, chapter.charCount),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
}

@Composable
private fun EditMetaDialog(book: Book, onDismiss: () -> Unit, onSave: (String, String?, String?) -> Unit) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author ?: "") }
    var summary by remember { mutableStateOf(book.summary ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_edit_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.detail_field_title)) }, singleLine = true)
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text(stringResource(R.string.detail_field_author)) }, singleLine = true)
                OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text(stringResource(R.string.detail_field_description)) }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) onSave(title, author, summary)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun CharsetDialog(current: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val options = listOf("UTF-8", "GB18030", "Big5", "UTF-16LE")
    var selected by remember { mutableStateOf(current ?: "UTF-8") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_encoding_dialog)) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = option }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = { selected = option })
                        Text(option, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(selected) }) { Text(stringResource(R.string.detail_apply_reparse)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun formatWordCount(count: Long): String {
    val zh = java.util.Locale.getDefault().language == "zh"
    return when {
        zh && count >= 100_000_000 -> stringResource(R.string.word_count_yi, count / 100_000_000.0)
        zh && count >= 10_000 -> stringResource(R.string.word_count_wan, count / 10_000)
        !zh && count >= 1_000_000 -> stringResource(R.string.word_count_m, count / 1_000_000.0)
        !zh && count >= 1_000 -> stringResource(R.string.word_count_k, count / 1_000)
        else -> stringResource(R.string.word_count_chars, count)
    }
}
