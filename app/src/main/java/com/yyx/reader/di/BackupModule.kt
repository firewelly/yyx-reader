package com.yyx.reader.di

import com.yyx.reader.core.domain.repository.BackupRepository
import com.yyx.reader.data.backup.BackupRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 备份 / 恢复依赖绑定（PRD F-12）。
 * 独立模块，避免改动共享的 AppModule.kt。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository
}
