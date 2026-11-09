package com.yyx.reader

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.entity.BookEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F-13 标签 UI 层 E2E（真实 MainActivity + Compose 语义树）：
 * 长按书架书籍进入多选 → 选择栏「标签」入口 → 批量打标面板 → 输入中文新标签「玄幻」→
 * 新建并自动勾选 → 应用 → 校验书架出现标签 chip 且数据库写入成功。
 *
 * 说明：`adb shell input text` 无法注入中文（AOSP 版 input 命令对非 ASCII 抛 NPE），
 * 因此「输入中文标签名」这一步在本仪器测试中完成；筛选择/删除等步骤另用 adb UI 自动化覆盖。
 *
 * 为保证用例自包含，种子书籍在 @BeforeClass 直接写入真实库（Activity 启动前），
 * @AfterClass 删除该书；新建的标签保留给后续 E2E 手工步骤使用。
 */
@RunWith(AndroidJUnit4::class)
class TagUiFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val TAG_NAME = "玄幻"
        private const val BOOK_TITLE = "QA标签UI书"
        private const val BOOK_URI = "file:///qa/ui-tag-flow.txt"
        private var seededBookId: Long = 0

        @JvmStatic
        @BeforeClass
        fun seedShelf(): Unit = runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = YYXDatabase.build(context)
            try {
                // 清理上次中断留下的同名种子书 / 同名标签，保证可重复执行
                db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE file_uri = ?", arrayOf(BOOK_URI))
                db.tagDao().observeTags().first()
                    .filter { it.name == TAG_NAME }
                    .forEach { db.tagDao().deleteTag(it.id) }
                val now = System.currentTimeMillis()
                seededBookId = db.bookDao().insert(
                    BookEntity(
                        title = BOOK_TITLE,
                        format = "txt",
                        fileUri = BOOK_URI,
                        charset = "UTF-8",
                        chapterCount = 1,
                        wordCount = 20,
                        addedAt = now,
                        updatedAt = now,
                    ),
                )
                assertTrue("种子书籍写入失败", seededBookId > 0)
            } finally {
                db.close()
            }
        }

        @JvmStatic
        @AfterClass
        fun cleanShelf(): Unit = runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = YYXDatabase.build(context)
            try {
                // 只删种子书（book_tags 级联清理）；标签保留供后续 E2E 使用
                db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE file_uri = ?", arrayOf(BOOK_URI))
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun tagViaBatchSheetUiFlow() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // 多语言：UI 文案从资源解析（随设备语言）
        val selected1 = ctx.getString(com.yyx.reader.R.string.library_selected_count, 1)
        val tagsDesc = ctx.getString(com.yyx.reader.R.string.library_tags)
        val sheetTitle = ctx.getString(com.yyx.reader.R.string.library_tag_panel_title)
        val create = ctx.getString(com.yyx.reader.R.string.library_create)
        val applyTo1 = ctx.getString(com.yyx.reader.R.string.library_apply_to, 1)

        // 1) 书架出现种子书（Activity 启动时读取真实库）
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(BOOK_TITLE).fetchSemanticsNodes().isNotEmpty()
        }

        // 2) 长按进入多选，选择栏出现「已选择 1」
        compose.onAllNodesWithText(BOOK_TITLE)[0].performTouchInput { longClick() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(selected1).fetchSemanticsNodes().isNotEmpty()
        }

        // 3) 打开批量打标面板
        compose.onNodeWithContentDescription(tagsDesc).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(sheetTitle).fetchSemanticsNodes().isNotEmpty()
        }

        // 4) 输入中文标签名并新建（自动勾选）
        compose.onNode(hasSetTextAction()).performTextInput(TAG_NAME)
        compose.onNodeWithText(create).performClick()

        // 5) 新建成功后自动勾选：等待面板内出现可勾选且已选中的标签行
        compose.waitUntil(5_000) {
            compose.onAllNodes(isOn()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(TAG_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        // 6) 应用（选择数 1）
        compose.onNodeWithText(applyTo1).performClick()

        // 7) 数据库落库：种子书拥有该标签
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = YYXDatabase.build(context)
            try {
                // 连跑 connected 测试时偶发 10s 超时（已知 flake，见 AGENTS「已知问题 12」），放宽到 20s
                withTimeout(20_000) {
                    while (db.tagDao().tagsOfBook(seededBookId).none { it.name == TAG_NAME }) {
                        delay(100)
                    }
                }
                assertEquals(listOf(TAG_NAME), db.tagDao().tagsOfBook(seededBookId).map { it.name })
            } finally {
                db.close()
            }
        }

        // 8) UI 生效：标签筛选 chip 出现、卡片标签摘要出现
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(TAG_NAME).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
