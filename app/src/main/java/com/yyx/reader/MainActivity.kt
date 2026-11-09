package com.yyx.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.yyx.reader.data.BookImporter
import com.yyx.reader.feature.reader.VolumeKeyHub
import com.yyx.reader.navigation.YYXNavHost
import com.yyx.reader.ui.theme.YYXTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var importer: BookImporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YYXTheme {
                YYXNavHost()
            }
        }
        handleImportIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    /** 接收第三方「分享 / 打开方式」的 TXT / EPUB（PRD F-01-09） */
    private fun handleImportIntent(intent: Intent?) {
        if (intent == null) return
        val streamUri: Uri? = if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else {
            intent.data
        }
        if (streamUri == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW) return

        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    val docName = runCatching {
                        DocumentFile.fromSingleUri(applicationContext, streamUri)?.name
                    }.getOrNull()
                    docName ?: intent.getStringExtra(Intent.EXTRA_TITLE) ?: streamUri.lastPathSegment
                }
                // 拷贝到私有 incoming/ 再导入：SEND 流式 Uri 无持久授权，file:// 副本稳定可读
                val target = withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "incoming").apply { mkdirs() }
                    val safeName = sanitize(name) ?: "import_${System.currentTimeMillis()}.txt"
                    val file = File(dir, uniqueName(dir, safeName))
                    contentResolver.openInputStream(streamUri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException(getString(R.string.share_import_unreadable))
                    file
                }
                val result = importer.importDocument(DocumentFile.fromFile(target))
                val message = when (result.outcome) {
                    BookImporter.Outcome.ADDED -> getString(R.string.share_import_success, target.nameWithoutExtension)
                    BookImporter.Outcome.UPDATED -> getString(R.string.share_import_updated)
                    BookImporter.Outcome.SKIPPED -> getString(R.string.share_import_unchanged)
                    BookImporter.Outcome.FAILED -> getString(R.string.share_import_failed, result.message.orEmpty())
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.share_import_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sanitize(name: String?): String? {
        val cleaned = name?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.takeIf { it.isNotBlank() } ?: return null
        return if (Regex("\\.(txt|epub)$", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) cleaned else "$cleaned.txt"
    }

    private fun uniqueName(dir: File, name: String): String {
        var candidate = name
        var i = 1
        while (File(dir, candidate).exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) {
                "${name.substring(0, dot)}_$i${name.substring(dot)}"
            } else {
                "${name}_$i"
            }
            i++
        }
        return candidate
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 阅读器前台时音量键翻页（F-04-08）
        if (VolumeKeyHub.handler?.invoke(keyCode) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}
