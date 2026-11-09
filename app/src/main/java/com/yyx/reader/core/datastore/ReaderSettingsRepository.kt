package com.yyx.reader.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.readerDataStore by preferencesDataStore(name = "reader_settings")

/** 阅读默认设置（全局；按书覆盖为 V1.1） */
data class ReaderSettings(
    val fontSizeSp: Int = 18,
    val lineSpacingDp: Int = 6,
    val theme: String = "DAY",
    val keepScreenOn: Boolean = true,
)

/** 听书设置（语速/引擎/输出路由），全局持久化 */
data class ListenSettings(
    val rate: Float = 1.0f,
    /** 用户指定 TTS 引擎包名；null = 自动（系统默认 → 首个已安装） */
    val enginePackage: String? = null,
    /**
     * 听书时强制外放（V1.3 F-08 降级实现）：直读模式只能临时强制扬声器，
     * 有线/蓝牙仍跟随系统；精确路由需 V2 合成管线。默认 false = 完全跟随系统。
     */
    val forceSpeaker: Boolean = false,
    /**
     * 首选输出设备路由选择器（V2 精确音频路由，仅本地引擎消费）：
     * `t<type>` / `t<type>:<address>`（[com.yyx.reader.core.audio.deviceRouteKey]），
     * null = 跟随系统。地址跨设备不可迁移，恢复备份后匹配不到自然回退。
     */
    val preferredOutput: String? = null,
)

@Singleton
class ReaderSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val FONT_SIZE = intPreferencesKey("font_size")
    private val LINE_SPACING = intPreferencesKey("line_spacing")
    private val THEME = stringPreferencesKey("theme")
    private val TTS_RATE = floatPreferencesKey("tts_rate")
    private val TTS_ENGINE = stringPreferencesKey("tts_engine")
    private val TTS_FORCE_SPEAKER = booleanPreferencesKey("tts_force_speaker")
    private val TTS_PREFERRED_OUTPUT = stringPreferencesKey("tts_preferred_output")

    val settings: Flow<ReaderSettings> = context.readerDataStore.data.map { prefs ->
        ReaderSettings(
            fontSizeSp = prefs[FONT_SIZE] ?: 18,
            lineSpacingDp = prefs[LINE_SPACING] ?: 6,
            theme = prefs[THEME] ?: "DAY",
        )
    }

    val listenSettings: Flow<ListenSettings> = context.readerDataStore.data.map { prefs ->
        ListenSettings(
            rate = prefs[TTS_RATE] ?: 1.0f,
            enginePackage = prefs[TTS_ENGINE],
            forceSpeaker = prefs[TTS_FORCE_SPEAKER] ?: false,
            preferredOutput = prefs[TTS_PREFERRED_OUTPUT],
        )
    }

    suspend fun setFontSize(sizeSp: Int) {
        context.readerDataStore.edit { it[FONT_SIZE] = sizeSp.coerceIn(12, 32) }
    }

    suspend fun setLineSpacing(dp: Int) {
        context.readerDataStore.edit { it[LINE_SPACING] = dp.coerceIn(0, 20) }
    }

    suspend fun setTheme(theme: String) {
        context.readerDataStore.edit { it[THEME] = theme }
    }

    suspend fun setTtsRate(rate: Float) {
        context.readerDataStore.edit { it[TTS_RATE] = rate.coerceIn(0.5f, 3.0f) }
    }

    /** 听书强制外放（V1.3 F-08）：持久化到 tts_force_speaker */
    suspend fun setForceSpeaker(enabled: Boolean) {
        context.readerDataStore.edit { it[TTS_FORCE_SPEAKER] = enabled }
    }

    suspend fun setTtsEngine(packageName: String?) {
        context.readerDataStore.edit { prefs ->
            if (packageName == null) prefs.remove(TTS_ENGINE) else prefs[TTS_ENGINE] = packageName
        }
    }

    /** 首选输出设备（V2 精确音频路由，仅本地引擎消费）；null = 跟随系统 */
    suspend fun setPreferredOutput(routeKey: String?) {
        context.readerDataStore.edit { prefs ->
            if (routeKey.isNullOrBlank()) prefs.remove(TTS_PREFERRED_OUTPUT) else prefs[TTS_PREFERRED_OUTPUT] = routeKey
        }
    }

    // ---------- 备份 / 恢复（PRD F-12）：一次性读取与整组写回 ----------

    /** 当前阅读设置快照（导出备份用） */
    suspend fun currentReaderSettings(): ReaderSettings = settings.first()

    /** 当前听书设置快照（导出备份用） */
    suspend fun currentListenSettings(): ListenSettings = listenSettings.first()

    /** 整组应用阅读设置（恢复备份用）；越界值收敛到既有合法区间 */
    suspend fun applyReaderSettings(value: ReaderSettings) {
        context.readerDataStore.edit { prefs ->
            prefs[FONT_SIZE] = value.fontSizeSp.coerceIn(12, 32)
            prefs[LINE_SPACING] = value.lineSpacingDp.coerceIn(0, 20)
            prefs[THEME] = value.theme
        }
    }

    /** 整组应用听书设置（恢复备份用）；enginePackage=null 表示恢复为「自动」 */
    suspend fun applyListenSettings(value: ListenSettings) {
        context.readerDataStore.edit { prefs ->
            prefs[TTS_RATE] = value.rate.coerceIn(0.5f, 3.0f)
            prefs[TTS_FORCE_SPEAKER] = value.forceSpeaker
            if (value.enginePackage == null) {
                prefs.remove(TTS_ENGINE)
            } else {
                prefs[TTS_ENGINE] = value.enginePackage
            }
            if (value.preferredOutput.isNullOrBlank()) {
                prefs.remove(TTS_PREFERRED_OUTPUT)
            } else {
                prefs[TTS_PREFERRED_OUTPUT] = value.preferredOutput
            }
        }
    }
}
