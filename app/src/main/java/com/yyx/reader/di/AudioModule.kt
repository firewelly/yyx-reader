package com.yyx.reader.di

import com.yyx.reader.core.audio.AudioOutputRepositoryImpl
import com.yyx.reader.core.audio.OutputDeviceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 听书输出路由（V1.3 F-08）依赖绑定。
 * 独立模块，避免改动共享的 AppModule.kt（与 BackupModule / TagModule 一致）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindOutputDeviceRepository(impl: AudioOutputRepositoryImpl): OutputDeviceRepository
}
