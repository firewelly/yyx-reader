package com.yyx.reader.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.yyx.reader.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yyx.reader.core.tts.TtsEngineRouter
import com.yyx.reader.data.backup.BackupFormat

/**
 * 设置页（PRD F-09 / F-12）：
 * 听书引擎选择 / 存储管理 / 备份与恢复 / 崩溃日志 / 关于与合规声明
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val listen by viewModel.listenSettings.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val crashLogs by viewModel.crashLogs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val localModel by viewModel.localModel.collectAsStateWithLifecycle()
    val modelImportCopied by viewModel.modelImportCopied.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showEngineDialog by remember { mutableStateOf(false) }
    var showCrashDialog by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // 导出：系统「创建文档」选择器（SAF），默认文件名 yyx-backup-yyyyMMdd-HHmm.json
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE),
    ) { uri -> uri?.let(viewModel::exportBackup) }

    // 离线语音模型：系统「选择目录」选择器（SAF 树），选中即校验导入
    val modelImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::importLocalModel) }

    // 导入：系统「打开文档」选择器；选中后先弹覆盖式恢复二次确认
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportConfirm = true
        }
    }


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
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_listen_section))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_tts_engine)) },
                supportingContent = {
                    val selected = engines.firstOrNull { it.packageName == listen?.enginePackage }
                    Text(
                        when {
                            listen?.enginePackage == TtsEngineRouter.LOCAL_ENGINE_ID ->
                                stringResource(R.string.settings_engine_local_selected)
                            engines.isEmpty() && localModel != null ->
                                stringResource(R.string.settings_engine_local_selected)
                            engines.isEmpty() -> stringResource(R.string.settings_no_engine)
                            selected != null -> stringResource(R.string.settings_engine_selected, selected.label)
                            listen?.enginePackage == null && engines.isNotEmpty() ->
                                stringResource(R.string.settings_engine_auto_with, engines.first().label)
                            else -> stringResource(R.string.settings_engine_uninstalled)
                        },
                        fontSize = 13.sp,
                    )
                },
                modifier = Modifier.clickable(enabled = engines.isNotEmpty() || localModel != null) {
                    showEngineDialog = true
                },
            )

            // V1.5：内置离线引擎（sherpa-onnx）语音模型管理（模型不内置 APK，SAF 导入）
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_local_model_section))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_local_model_title)) },
                supportingContent = {
                    Text(
                        localModel?.let { stringResource(R.string.settings_local_model_imported, it.label, SettingsViewModel.formatSize(it.sizeBytes)) }
                            ?: stringResource(R.string.settings_local_model_hint),
                        fontSize = 13.sp,
                    )
                },
                trailingContent = {
                    if (modelImportCopied >= 0) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Row {
                            TextButton(onClick = { modelImportLauncher.launch(null) }) { Text(stringResource(R.string.common_import), fontSize = 13.sp) }
                            if (localModel != null) {
                                TextButton(onClick = viewModel::removeLocalModel) { Text(stringResource(R.string.common_remove), fontSize = 13.sp) }
                            }
                        }
                    }
                },
            )
            if (modelImportCopied >= 0) {
                // SAF 无法预知总大小，进度用非确定式 + 已拷贝字节提示
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Text(
                    stringResource(R.string.settings_model_importing, SettingsViewModel.formatSize(modelImportCopied.coerceAtLeast(0L))),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_storage_section))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_db)) },
                supportingContent = { Text(SettingsViewModel.formatSize(storage.databaseBytes), fontSize = 13.sp) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_epub_cache)) },
                supportingContent = { Text(SettingsViewModel.formatSize(storage.epubCacheBytes), fontSize = 13.sp) },
                trailingContent = {
                    TextButton(onClick = viewModel::clearEpubCache, enabled = storage.epubCacheBytes > 0) {
                        Text(stringResource(R.string.common_clear), fontSize = 13.sp)
                    }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_tts_cache)) },
                supportingContent = { Text(stringResource(R.string.settings_tts_cache_hint), fontSize = 13.sp) },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_backup_section))
            if (backupBusy) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_backup_busy)) },
                    trailingContent = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_backup_export)) },
                supportingContent = {
                    Text(
                        stringResource(R.string.settings_backup_export_hint),
                        fontSize = 13.sp,
                    )
                },
                trailingContent = {
                    TextButton(
                        enabled = !backupBusy,
                        onClick = { exportLauncher.launch(BackupFormat.defaultFileName()) },
                    ) { Text(stringResource(R.string.common_export), fontSize = 13.sp) }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_backup_import)) },
                supportingContent = {
                    Text(
                        stringResource(R.string.settings_backup_import_hint),
                        fontSize = 13.sp,
                    )
                },
                trailingContent = {
                    TextButton(
                        enabled = !backupBusy,
                        onClick = {
                            // P2-6：收紧选择器类型（不再放开 */*），避免误选任意文件
                            importLauncher.launch(
                                arrayOf(
                                    BackupFormat.MIME_TYPE,
                                    "text/plain",
                                    "application/octet-stream",
                                ),
                            )
                        },
                    ) { Text(stringResource(R.string.common_import), fontSize = 13.sp) }
                },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_diagnostics_section))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_crash_logs)) },
                supportingContent = {
                    Text(
                        if (crashLogs.isEmpty()) stringResource(R.string.settings_crash_none) else stringResource(R.string.settings_crash_count, crashLogs.size),
                        fontSize = 13.sp,
                    )
                },
                trailingContent = {
                    if (crashLogs.isNotEmpty()) {
                        Row {
                            TextButton(onClick = { showCrashDialog = true }) { Text(stringResource(R.string.common_view), fontSize = 13.sp) }
                            TextButton(onClick = viewModel::clearCrashLogs) { Text(stringResource(R.string.common_clear), fontSize = 13.sp) }
                        }
                    }
                },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_about_section))
            ListItem(headlineContent = { Text(stringResource(R.string.app_name)) }, supportingContent = { Text("v0.1.0-offline · V1.5") })
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_principles)) },
                supportingContent = {
                    Text(
                        stringResource(R.string.settings_principles_text),
                        fontSize = 13.sp,
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_data_note)) },
                supportingContent = {
                    Text(
                        stringResource(R.string.settings_data_note_text),
                        fontSize = 13.sp,
                    )
                },
            )
        }
    }

    if (showEngineDialog) {
        AlertDialog(
            onDismissRequest = { showEngineDialog = false },
            title = { Text(stringResource(R.string.settings_engine_dialog_title)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectEngine(null)
                                showEngineDialog = false
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = listen?.enginePackage == null, onClick = null)
                        Text(stringResource(R.string.settings_engine_auto), fontSize = 14.sp)
                    }
                    engines.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectEngine(engine.packageName)
                                    showEngineDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = listen?.enginePackage == engine.packageName,
                                onClick = null,
                            )
                            Text(engine.label, fontSize = 14.sp)
                        }
                    }
                    // 内置离线引擎（V1.5 备选）：默认推荐系统离线 TTS；本地模型合成性能要求高（真机实测 RTF≈1.3，
                    // 句间停顿明显），仅作为无可用系统引擎设备的兜底，故排在系统引擎之后
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = localModel != null) {
                                viewModel.selectEngine(TtsEngineRouter.LOCAL_ENGINE_ID)
                                showEngineDialog = false
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = listen?.enginePackage == TtsEngineRouter.LOCAL_ENGINE_ID,
                            onClick = null,
                        )
                        Column {
                            Text(stringResource(R.string.settings_local_engine_option), fontSize = 14.sp)
                            Text(
                                localModel?.let { stringResource(R.string.settings_local_engine_imported_hint, it.label) }
                                    ?: stringResource(R.string.settings_local_engine_missing_hint),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEngineDialog = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }

    if (showCrashDialog) {
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            title = { Text(stringResource(R.string.settings_crash_logs)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    crashLogs.take(5).forEach { file ->
                        val preview = runCatching { file.readText().take(200) }.getOrDefault("")
                        Text(
                            text = "${file.name}\n$preview…",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCrashDialog = false }) { Text(stringResource(R.string.common_close)) } },
        )
    }

    // 覆盖式恢复二次确认（PRD F-12：导入会清空当前全部数据，不可撤销）
    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirm = false
                pendingImportUri = null
            },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_import_confirm_text),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        pendingImportUri?.let(viewModel::importBackup)
                        pendingImportUri = null
                    },
                ) { Text(stringResource(R.string.settings_import_confirm_button)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        pendingImportUri = null
                    },
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
