package com.yyx.reader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yyx.reader.core.database.dao.BookDao
import com.yyx.reader.core.database.dao.ChapterDao
import com.yyx.reader.core.database.dao.ProgressDao
import com.yyx.reader.core.database.dao.ScanFolderDao
import com.yyx.reader.core.database.dao.TagDao
import com.yyx.reader.core.database.entity.BookEntity
import com.yyx.reader.core.database.entity.BookTagEntity
import com.yyx.reader.core.database.entity.BookmarkEntity
import com.yyx.reader.core.database.entity.ChapterEntity
import com.yyx.reader.core.database.entity.ReadingProgressEntity
import com.yyx.reader.core.database.entity.ScanFolderEntity
import com.yyx.reader.core.database.entity.TagEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        ScanFolderEntity::class,
        BookmarkEntity::class,
        TagEntity::class,
        BookTagEntity::class,
    ],
    version = 2,
    // V1.3：导出 schema 到 app/schemas/（供迁移测试与后续 v2→v3 基准）
    exportSchema = true,
)
abstract class YYXDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun scanFolderDao(): ScanFolderDao
    abstract fun bookmarkDao(): com.yyx.reader.core.database.dao.BookmarkDao
    abstract fun tagDao(): TagDao

    companion object {
        const val NAME = "yyx.db"

        /**
         * v1 → v2（F-13 标签与分组）：
         * 建表/索引语句与 Room 为 TagEntity/BookTagEntity 生成的 createAllTables/createAllIndices 逐字一致，
         * 否则打开旧库会抛 "Migration didn't properly handle"。
         *
         * internal（而非 private）：供 androidTest 的 MigrationV1ToV2Test 构造 v1 库后显式传入验证。
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_tags` (`book_id` INTEGER NOT NULL, `tag_id` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`book_id`, `tag_id`), " +
                        "FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_tags_book_id` ON `book_tags` (`book_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_tags_tag_id` ON `book_tags` (`tag_id`)")
            }
        }

        fun build(context: Context): YYXDatabase =
            Room.databaseBuilder(context, YYXDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // 仅 book_tags 有 DB 外键；chapters / reading_progress / bookmarks 由
                        // BookRepositoryImpl.deleteBook 在事务内显式清理（见 V1.3 修复）
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
