package com.yyx.reader.feature.bookdetail

import com.yyx.reader.R
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.Chapter
import com.yyx.reader.core.domain.model.ProgressInfo
import com.yyx.reader.core.domain.repository.BookRepository
import com.yyx.reader.core.domain.repository.ChapterRepository
import com.yyx.reader.core.domain.repository.ProgressRepository
import com.yyx.reader.data.BookImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val progressRepository: ProgressRepository,
    private val importer: BookImporter,
) : ViewModel() {

    val bookId: Long = checkNotNull(savedStateHandle["bookId"])

    val book: StateFlow<Book?> = bookRepository.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<Chapter>> = chapterRepository.observeChapters(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<ProgressInfo?> = progressRepository.observe(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun updateMetadata(title: String, author: String?, summary: String?) {
        viewModelScope.launch {
            bookRepository.updateMetadata(bookId, title, author, summary)
            _message.value = context.getString(R.string.detail_saved)
        }
    }

    fun setCharset(charset: String) {
        viewModelScope.launch {
            bookRepository.updateCharset(bookId, charset)
            reparse()
        }
    }

    fun reparse() {
        viewModelScope.launch {
            val book = bookRepository.getBook(bookId) ?: return@launch
            val doc = DocumentFile.fromSingleUri(context, Uri.parse(book.fileUri))
            if (doc == null || !doc.exists()) {
                _message.value = context.getString(R.string.detail_source_unavailable)
                return@launch
            }
            val result = importer.importDocument(doc, force = true)
            _message.value = when (result.outcome) {
                BookImporter.Outcome.ADDED, BookImporter.Outcome.UPDATED -> context.getString(R.string.detail_reparse_done)
                BookImporter.Outcome.SKIPPED -> context.getString(R.string.detail_reparse_unchanged)
                BookImporter.Outcome.FAILED -> context.getString(R.string.detail_reparse_failed, result.message.orEmpty())
            }
        }
    }

    fun delete(deleteSourceFile: Boolean, onDeleted: () -> Unit) {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId, deleteSourceFile)
            onDeleted()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
