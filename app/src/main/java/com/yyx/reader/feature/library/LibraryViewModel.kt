package com.yyx.reader.feature.library

import com.yyx.reader.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yyx.reader.core.domain.model.ScanFolder
import com.yyx.reader.core.domain.model.ScanReport
import com.yyx.reader.core.domain.model.ShelfBook
import com.yyx.reader.core.domain.model.Tag
import com.yyx.reader.core.domain.repository.BookRepository
import com.yyx.reader.core.domain.repository.ScanFolderRepository
import com.yyx.reader.core.domain.repository.TagRepository
import com.yyx.reader.data.LibraryScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val scanFolderRepository: ScanFolderRepository,
    private val tagRepository: TagRepository,
    private val scanner: LibraryScanner,
) : ViewModel() {

    data class UiState(
        val books: List<ShelfBook> = emptyList(),
        val folders: List<ScanFolder> = emptyList(),
        /** 全部标签（书架顶部筛选 chip） */
        val tags: List<Tag> = emptyList(),
        /** bookId → 标签（卡片展示 + 标签筛选 + 批量打标初始勾选） */
        val tagMap: Map<Long, List<Tag>> = emptyMap(),
        /** 当前标签筛选；null = 全部 */
        val filterTagId: Long? = null,
        val query: String = "",
        val sort: Sort = Sort.RECENT,
        val searching: Boolean = false,
        val scanning: Boolean = false,
        val scanProgress: String = "",
        val scanReport: ScanReport? = null,
        val selection: Set<Long> = emptySet(),
        val message: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var allBooks: List<ShelfBook> = emptyList()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                bookRepository.observeShelf(),
                scanFolderRepository.observeFolders(),
                tagRepository.observeAll(),
                tagRepository.observeTagMap(),
            ) { books, folders, tags, tagMap -> ShelfData(books, folders, tags, tagMap) }
                .collect { data ->
                    allBooks = data.books
                    _ui.update {
                        // P2-5c：标签数据被整体替换（恢复备份）或被外部删除后，当前筛选标签可能已不存在，
                        // 自动回到「全部」并按新标签映射重算书架，避免停在空态/错误筛选
                        val filter = it.filterTagId?.takeIf { id -> data.tags.any { tag -> tag.id == id } }
                        it.copy(
                            books = derive(allBooks, it.query, it.sort, filter, data.tagMap),
                            folders = data.folders,
                            tags = data.tags,
                            tagMap = data.tagMap,
                            filterTagId = filter,
                        )
                    }
                }
        }
    }

    fun setQuery(q: String) = _ui.update {
        it.copy(query = q, books = derive(allBooks, q, it.sort, it.filterTagId, it.tagMap))
    }

    fun setSearching(active: Boolean) = _ui.update {
        val query = if (active) it.query else ""
        it.copy(searching = active, query = query, books = derive(allBooks, query, it.sort, it.filterTagId, it.tagMap))
    }

    fun setSort(sort: Sort) = _ui.update {
        it.copy(sort = sort, books = derive(allBooks, it.query, sort, it.filterTagId, it.tagMap))
    }

    /** 标签筛选单选：再点当前标签回到「全部」 */
    fun setFilterTag(tagId: Long?) = _ui.update {
        val next = if (it.filterTagId == tagId) null else tagId
        it.copy(filterTagId = next, books = derive(allBooks, it.query, it.sort, next, it.tagMap))
    }

    /** 新建标签；成功回调新 id（批量面板自动勾选），失败回调中文原因（面板内就地提示） */
    fun createTag(name: String, onCreated: (Long) -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = tagRepository.create(name)
            if (id == null) onError(context.getString(R.string.library_tag_empty_or_duplicate)) else onCreated(id)
        }
    }

    /** 删除标签并清空对应筛选；book_tags 关联由外键级联清理 */
    fun deleteTag(tag: Tag) {
        viewModelScope.launch {
            tagRepository.delete(tag.id)
            _ui.update {
                val next = if (it.filterTagId == tag.id) null else it.filterTagId
                // 同步重算列表：Flow 回灌前不能停在「当前筛选已删除但 books 仍是旧筛选结果」的空态
                it.copy(
                    filterTagId = next,
                    books = derive(allBooks, it.query, it.sort, next, it.tagMap),
                    message = context.getString(R.string.library_tag_deleted, tag.name),
                )
            }
        }
    }

    /** 批量打标：勾选的补上、取消勾选的移除（初始勾选 = 所选书籍标签交集） */
    fun applyTags(bookIds: Set<Long>, checkedTagIds: Set<Long>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch {
            val common = commonTagIds(bookIds, _ui.value.tagMap)
            (checkedTagIds - common).forEach { tagId -> tagRepository.addToBooks(bookIds, tagId) }
            (common - checkedTagIds).forEach { tagId -> tagRepository.removeFromBooks(bookIds, tagId) }
            clearSelection()
            _ui.update { it.copy(message = context.getString(R.string.library_tag_updated, bookIds.size)) }
        }
    }

    fun toggleSelect(id: Long) = _ui.update {
        val selection = it.selection.toMutableSet()
        if (!selection.add(id)) selection.remove(id)
        it.copy(selection = selection)
    }

    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    fun addFolder(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModelScope.launch {
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: context.getString(R.string.library_folder)
            val added = scanFolderRepository.add(uri.toString(), name)
            if (!added) _ui.update { it.copy(message = context.getString(R.string.library_folder_added)) }
        }
    }

    fun removeFolder(folder: ScanFolder) {
        viewModelScope.launch { scanFolderRepository.remove(folder) }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _ui.update { it.copy(scanning = true, scanReport = null, scanProgress = context.getString(R.string.library_collecting)) }
            try {
                val report = scanner.scan { processed, total, msg ->
                    _ui.update { it.copy(scanProgress = "$processed/$total · $msg") }
                }
                _ui.update {
                    it.copy(
                        scanning = false,
                        scanReport = report,
                        message = context.getString(R.string.scan_summary_added, report.added, report.updated, report.skipped, report.failed),
                    )
                }
            } catch (e: CancellationException) {
                _ui.update { it.copy(scanning = false, message = context.getString(R.string.library_scan_cancelled)) }
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(scanning = false, message = context.getString(R.string.library_scan_failed, e.message.orEmpty())) }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun deleteSelected(deleteSourceFile: Boolean) {
        val ids = _ui.value.selection
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { bookRepository.deleteBook(it, deleteSourceFile) }
            clearSelection()
            _ui.update { it.copy(message = context.getString(R.string.library_deleted_count, ids.size)) }
        }
    }

    /** 界面层直接投递提示（快捷授权完成等本地事件复用 snackbar 通道） */
    fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    private fun derive(
        books: List<ShelfBook>,
        query: String,
        sort: Sort,
        filterTagId: Long?,
        tagMap: Map<Long, List<Tag>>,
    ): List<ShelfBook> = filterShelf(books, query, sort, filterTagId, tagMap)

    /** combine 四路数据载荷（书籍/文件夹/标签/书籍标签映射） */
    private data class ShelfData(
        val books: List<ShelfBook>,
        val folders: List<ScanFolder>,
        val tags: List<Tag>,
        val tagMap: Map<Long, List<Tag>>,
    )
}
