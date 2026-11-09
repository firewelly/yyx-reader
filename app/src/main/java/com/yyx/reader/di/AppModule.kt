package com.yyx.reader.di

import android.content.Context
import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.dao.BookDao
import com.yyx.reader.core.database.dao.BookmarkDao
import com.yyx.reader.core.database.dao.ChapterDao
import com.yyx.reader.core.database.dao.ProgressDao
import com.yyx.reader.core.database.dao.ScanFolderDao
import com.yyx.reader.core.domain.repository.BookRepository
import com.yyx.reader.core.domain.repository.BookmarkRepository
import com.yyx.reader.core.domain.repository.ChapterRepository
import com.yyx.reader.core.domain.repository.ProgressRepository
import com.yyx.reader.core.domain.repository.ScanFolderRepository
import com.yyx.reader.data.repository.BookRepositoryImpl
import com.yyx.reader.data.repository.BookmarkRepositoryImpl
import com.yyx.reader.data.repository.ChapterRepositoryImpl
import com.yyx.reader.data.repository.ProgressRepositoryImpl
import com.yyx.reader.data.repository.ScanFolderRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): YYXDatabase =
        YYXDatabase.build(context)

    @Provides
    fun provideBookDao(db: YYXDatabase): BookDao = db.bookDao()

    @Provides
    fun provideChapterDao(db: YYXDatabase): ChapterDao = db.chapterDao()

    @Provides
    fun provideProgressDao(db: YYXDatabase): ProgressDao = db.progressDao()

    @Provides
    fun provideScanFolderDao(db: YYXDatabase): ScanFolderDao = db.scanFolderDao()

    @Provides
    fun provideBookmarkDao(db: YYXDatabase): BookmarkDao = db.bookmarkDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository

    @Binds
    @Singleton
    abstract fun bindChapterRepository(impl: ChapterRepositoryImpl): ChapterRepository

    @Binds
    @Singleton
    abstract fun bindProgressRepository(impl: ProgressRepositoryImpl): ProgressRepository

    @Binds
    @Singleton
    abstract fun bindScanFolderRepository(impl: ScanFolderRepositoryImpl): ScanFolderRepository

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository
}
