package com.yyx.reader

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yyx.reader.core.database.YYXDatabase

/**
 * QA 仪器测试公共支撑（V1.2：备份 / 标签）。
 *
 * 使用**独立命名的文件数据库**而非线上 `yyx.db`：
 * - 每次用例前后 `context.deleteDatabase(name)`，互不污染、也不影响设备上的真实书库；
 * - 显式复制线上 [YYXDatabase] 打开旧库时的 `PRAGMA foreign_keys = ON` 回调，
 *   才能真实验证 book_tags 的级联删除（Room 默认不开启外键）。
 */
object QaTestSupport {

    /** 建一个带外键约束测试库（schema 由 Room 自动建到当前版本 v2） */
    fun createDatabase(context: Context, name: String): YYXDatabase =
        Room.databaseBuilder(context, YYXDatabase::class.java, name)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // 与 YYXDatabase.build() 一致：级联删除依赖外键
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                },
            )
            .build()

    /** 统计任意表行数（验证级联清理） */
    fun rowCount(db: YYXDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** 当前库中已有表名（导入计划按表存在性跳过 tags / book_tags） */
    fun tableNames(db: YYXDatabase): Set<String> = buildSet {
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
            .use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
    }
}
