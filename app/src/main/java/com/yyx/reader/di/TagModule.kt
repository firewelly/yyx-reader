package com.yyx.reader.di

import com.yyx.reader.core.database.YYXDatabase
import com.yyx.reader.core.database.dao.TagDao
import com.yyx.reader.core.domain.repository.TagRepository
import com.yyx.reader.data.repository.TagRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 标签（F-13）依赖注入：单独成文件，不改共享的 AppModule */
@Module
@InstallIn(SingletonComponent::class)
object TagDatabaseModule {

    @Provides
    fun provideTagDao(db: YYXDatabase): TagDao = db.tagDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TagRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository
}
