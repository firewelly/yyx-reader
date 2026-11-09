package com.yyx.reader.di

import com.yyx.reader.core.tts.TtsEngine
import com.yyx.reader.core.tts.TtsEngineRouter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * TTS 依赖注入（V1.5）：此前 SystemTtsEngine 靠 @Inject 构造函数隐式提供；
 * 引入内置离线引擎后需要多态路由，故在此把 [TtsEngineRouter] 绑定为 [TtsEngine] 唯一实现。
 * 独立模块文件，避免改动共享的 AppModule.kt（与 AudioModule 同惯例）。
 */
@InstallIn(SingletonComponent::class)
@Module
abstract class TtsModule {

    @Binds
    @Singleton
    abstract fun bindTtsEngine(impl: TtsEngineRouter): TtsEngine
}
