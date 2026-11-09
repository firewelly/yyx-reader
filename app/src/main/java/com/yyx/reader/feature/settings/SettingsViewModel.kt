package com.yyx.reader.feature.settings

import com.yyx.reader.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yyx.reader.core.datastore.ListenSettings
import com.yyx.reader.core.datastore.ReaderSettingsRepository
import com.yyx.reader.core.domain.model.BackupResult
import com.yyx.reader.core.domain.repository.BackupRepository
import com.yyx.reader.core.tts.InstalledEngine
import com.yyx.reader.core.tts.LocalModelLocator
import com.yyx.reader.core.tts.LocalTtsEngine
import com.yyx.reader.core.tts.SystemTtsEngine
import com.yyx.reader.core.tts.TtsEngineRouter
import com.yyx.reader.service.tts.TtsPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: ReaderSettingsRepository,
    private val ttsEngine: TtsEngineRouter,
    private val backupRepository: BackupRepository,
    private val playbackController: TtsPlaybackController,
) : ViewModel() {

    val listenSettings: StateFlow<ListenSettings?> = settingsRepository.listenSettings
        .map { it as ListenSettings? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _engines = MutableStateFlow<List<InstalledEngine>>(emptyList())
    val engines: StateFlow<List<InstalledEngine>> = _engines.asStateFlow()

    /** 已导入的离线语音模型（files/tts_model）；null = 未导入 */
    data class LocalModelInfo(val label: String, val sizeBytes: Long)

    private val _localModel = MutableStateFlow<LocalModelInfo?>(null)
    val localModel: StateFlow<LocalModelInfo?> = _localModel.asStateFlow()

    /** 模型导入执行中（按钮禁用 + 进度条）；null = 空闲，否则为已拷贝字节数 */
    private val _modelImportCopied = MutableStateFlow(-1L)
    val modelImportCopied: StateFlow<Long> = _modelImportCopied.asStateFlow()

    data class StorageInfo(
        val databaseBytes: Long = 0,
        val epubCacheBytes: Long = 0,
        val logBytes: Long = 0,
    )

    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()

    private val _crashLogs = MutableStateFlow<List<File>>(emptyList())
    val crashLogs: StateFlow<List<File>> = _crashLogs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 备份 / 恢复执行中（按钮禁用 + 进度提示） */
    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _engines.value = SystemTtsEngine.listEngines(context)
        _storage.value = computeStorage()
        _crashLogs.value = crashLogFiles()
        _localModel.value = readLocalModel()
    }

    fun selectEngine(packageName: String?) {
        viewModelScope.launch {
            settingsRepository.setTtsEngine(packageName)
            ttsEngine.useEngine(packageName) // 立即生效（下次 init 按新引擎）
            _message.value = context.getString(R.string.settings_engine_switched)
        }
    }

    // ---------- 离线语音模型（V1.5，sherpa-onnx） ----------

    private fun readLocalModel(): LocalModelInfo? {
        val layout = LocalTtsEngine.installedLayout(context) ?: return null
        val size = LocalTtsEngine.modelDir(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return LocalModelInfo(modelLabelText(layout.label), size)
    }

    /**
     * SAF 目录导入离线语音模型：先在 SAF 上探测校验（不动本机现有模型）→
     * 流式拷贝到临时目录（边拷边报进度）→ 拷贝后复检 → 原子替换 files/tts_model → 引擎失效待重载。
     */
    fun importLocalModel(treeUri: Uri) {
        if (_modelImportCopied.value >= 0) return // 导入中
        viewModelScope.launch(Dispatchers.IO) {
            _modelImportCopied.value = 0L
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                if (root == null) {
                    _message.value = context.getString(R.string.settings_model_import_dir_unreadable)
                    return@launch
                }
                val relPaths = listRelativePaths(root)
                val layout = LocalModelLocator.locate(relPaths).getOrElse { err ->
                    _message.value = context.getString(R.string.settings_model_import_failed, locateErrorText(err))
                    return@launch
                }

                val tmp = File(context.filesDir, "${LocalTtsEngine.MODEL_DIR_NAME}_tmp")
                tmp.deleteRecursively()
                tmp.mkdirs()
                val copied = copyModelFiles(root, tmp)
                if (LocalTtsEngine.installedLayoutAt(tmp) == null) {
                    tmp.deleteRecursively()
                    _message.value = context.getString(R.string.settings_model_import_verify_failed)
                    return@launch
                }

                val final = LocalTtsEngine.modelDir(context)
                if (final.exists()) final.deleteRecursively()
                if (!tmp.renameTo(final)) {
                    tmp.copyRecursively(final)
                    tmp.deleteRecursively()
                }
                ttsEngine.invalidateLocalEngine() // 引擎已加载旧模型时丢弃，下次 init 用新模型
                refresh()
                _message.value = context.getString(R.string.settings_model_imported, modelLabelText(layout.label), formatSize(copied))
            } finally {
                _modelImportCopied.value = -1L
            }
        }
    }

    /** 移除已导入的离线语音模型（可随时重新导入） */
    fun removeLocalModel() {
        LocalTtsEngine.modelDir(context).deleteRecursively()
        ttsEngine.invalidateLocalEngine()
        refresh()
        _message.value = context.getString(R.string.settings_model_removed)
    }

    /** 递归列出 SAF 目录内全部相对路径（含子目录，如 "dict/jieba.dict.utf8"） */
    private fun listRelativePaths(dir: DocumentFile, prefix: String = ""): Set<String> {
        val out = mutableSetOf<String>()
        dir.listFiles().forEach { f ->
            val name = f.name ?: return@forEach
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (f.isDirectory) out += listRelativePaths(f, rel) else out += rel
        }
        return out
    }

    /** 把所选目录全部文件按相对结构拷入 [dst]，累计返回拷贝字节数（进度实时上报） */
    private fun copyModelFiles(root: DocumentFile, dst: File): Long {
        var total = 0L
        fun walk(dir: DocumentFile, rel: String) {
            dst.mkdirs()
            dir.listFiles().forEach { f ->
                val name = f.name ?: return@forEach
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                if (f.isDirectory) {
                    walk(f, childRel)
                } else {
                    val out = File(dst, childRel)
                    out.parentFile?.mkdirs()
                    runCatching {
                        context.contentResolver.openInputStream(f.uri)?.use { input ->
                            out.outputStream().use { output ->
                                val buf = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    total += n
                                    _modelImportCopied.value = total
                                }
                            }
                        } ?: error(context.getString(R.string.backup_notification_unreadable, f.uri.toString()))
                    }.onFailure { throw IllegalStateException(context.getString(R.string.backup_copy_failed, childRel, it.message.orEmpty())) }
                }
            }
        }
        walk(root, "")
        return total
    }

    /** 清理可再生的 EPUB 文本缓存 */
    fun clearEpubCache() {
        viewModelScope.launch {
            val dir = File(context.filesDir, "epub_text")
            val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            dir.deleteRecursively()
            _storage.value = computeStorage()
            _message.value = context.getString(R.string.settings_cleanup_done, formatSize(freed))
        }
    }

    fun clearCrashLogs() {
        viewModelScope.launch {
            crashLogFiles().forEach { it.delete() }
            _crashLogs.value = crashLogFiles()
            _storage.value = computeStorage()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---------- 备份与恢复（PRD F-12） ----------

    /** 导出全量备份（SAF 写入 uri） */
    fun exportBackup(uri: Uri) {
        if (_backupBusy.value) return
        viewModelScope.launch {
            _backupBusy.value = true
            when (val result = backupRepository.export(uri)) {
                is BackupResult.Success -> {
                    val s = result.summary
                    val skipped = if (s.skippedTables.isEmpty()) {
                        ""
                    } else {
                        context.getString(R.string.settings_export_skipped, s.skippedTables.joinToString("/"))
                    }
                    _message.value = context.getString(R.string.settings_export_success, s.totalRows, formatSize(s.byteCount)) + skipped
                }
                is BackupResult.Failure -> _message.value = result.message
            }
            _backupBusy.value = false
        }
    }

    /** 覆盖式恢复（调用前 UI 已二次确认）：清空当前全部数据后从 uri 导入 */
    fun importBackup(uri: Uri) {
        if (_backupBusy.value) return
        // P2-5b：听书播放中拒绝恢复，避免服务持有已被替换的书籍 / 章节引用
        val playback = playbackController.state.value
        if (playback.active || playback.isPlaying) {
            _message.value = context.getString(R.string.settings_restore_stop_listen)
            return
        }
        viewModelScope.launch {
            _backupBusy.value = true
            when (val result = backupRepository.import(uri)) {
                is BackupResult.Success -> {
                    val s = result.summary
                    // P2-5a：设置已写回 DataStore，立即让 TTS 引擎选择生效（不必等下次启动）
                    val engineSwitched = if (s.settingsIncluded) {
                        runCatching {
                            val restored = settingsRepository.currentListenSettings()
                            ttsEngine.useEngine(restored.enginePackage)
                        }.isSuccess
                    } else {
                        false
                    }
                    val settingsText = when {
                        engineSwitched -> context.getString(R.string.settings_restore_settings_engine)
                        s.settingsIncluded -> context.getString(R.string.settings_restore_settings)
                        else -> ""
                    }
                    val warn = if (s.warnings.isEmpty()) "" else "；${s.warnings.joinToString("；")}"
                    _message.value = context.getString(R.string.settings_restore_success, s.totalRows) + settingsText + warn
                    // 数据库已整体替换：刷新存储概览与引擎列表
                    refresh()
                }
                is BackupResult.Failure -> _message.value = result.message
            }
            _backupBusy.value = false
        }
    }

    private fun computeStorage(): StorageInfo {
        val dbDir = context.getDatabasePath("yyx.db").parentFile ?: context.filesDir
        fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return StorageInfo(
            databaseBytes = dirSize(dbDir),
            epubCacheBytes = dirSize(File(context.filesDir, "epub_text")),
            logBytes = dirSize(File(context.filesDir, "logs")),
        )
    }

    private fun crashLogFiles(): List<File> =
        File(context.filesDir, "logs").listFiles()?.sortedByDescending { it.name } ?: emptyList()

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "${bytes / 1_000}KB"
            else -> "${bytes}B"
        }
    }

    /** 模型族标签 → 本地化文案 */
    private fun modelLabelText(label: LocalModelLocator.ModelLabel): String = context.getString(
        when (label) {
            LocalModelLocator.ModelLabel.VITS_ZH_INT8 -> R.string.model_label_vits_zh_int8
            LocalModelLocator.ModelLabel.VITS_ZH -> R.string.model_label_vits_zh
            LocalModelLocator.ModelLabel.MATCHA_ZH -> R.string.model_label_matcha_zh
            LocalModelLocator.ModelLabel.MATCHA_EN -> R.string.model_label_matcha_en
            LocalModelLocator.ModelLabel.KOKORO -> R.string.model_label_kokoro
        },
    )

    /** 探测错误 → 本地化文案 */
    private fun locateErrorText(t: Throwable): String {
        val e = t as? LocalModelLocator.LocateException ?: return t.message.orEmpty()
        return when (e.error) {
            LocalModelLocator.LocateError.EMPTY_DIR -> context.getString(R.string.model_error_empty_dir)
            LocalModelLocator.LocateError.MISSING_MODEL -> context.getString(R.string.model_error_missing_model, e.detail.orEmpty())
            LocalModelLocator.LocateError.MISSING_TOKENS -> context.getString(R.string.model_error_missing_tokens, e.detail.orEmpty())
            LocalModelLocator.LocateError.MATCHA_MISSING_DATA -> context.getString(R.string.model_error_matcha_data)
            LocalModelLocator.LocateError.VITS_MISSING_DICT -> context.getString(R.string.model_error_missing_dict)
            LocalModelLocator.LocateError.UNRECOGNIZED -> context.getString(R.string.model_error_unrecognized, e.detail.orEmpty())
        }
    }
}