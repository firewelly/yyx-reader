package com.yyx.reader.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 听书输出设备仓库（V1.3 F-08）：只读观察系统当前可用输出设备 */
interface OutputDeviceRepository {
    /**
     * 当前可用输出设备（已按类型去重计数并按分组排序）。
     * 设备插拔时自动更新；查询失败（无 AudioManager / 系统异常）返回空列表，不抛异常。
     */
    fun observeOutputs(): Flow<List<AudioOutput>>

    /**
     * 当前可用输出设备（V2 精确音频路由）：**逐设备**不去重（保留 id/address 供路由匹配），
     * 已过滤不可播放类型并排序。与 [observeOutputs] 共用同一回调源。
     */
    fun observeDevices(): Flow<List<AudioOutput>>
}

/**
 * [OutputDeviceRepository] 的 AudioManager 实现（直读模式，不依赖 Media3）。
 *
 * 设备枚举：[AudioManager.getDevices] + [AudioDeviceCallback]（插拔/切换即刷新），
 * 用 callbackFlow + awaitClose 保证收集结束时注销回调，避免泄漏。
 */
@Singleton
class AudioOutputRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : OutputDeviceRepository {

    override fun observeOutputs(): Flow<List<AudioOutput>> = observeFlow(dedup = true)

    override fun observeDevices(): Flow<List<AudioOutput>> = observeFlow(dedup = false)

    private fun observeFlow(dedup: Boolean): Flow<List<AudioOutput>> = callbackFlow {
        val audioManager = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()

        if (audioManager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(readOutputs(audioManager, dedup))
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(readOutputs(audioManager, dedup))
            }
        }

        // 注册失败（极少数 ROM 限制）也要保证先发一次当前快照
        runCatching { audioManager.registerAudioDeviceCallback(callback, null) }
            .onFailure { Log.w(TAG, "注册输出设备回调失败，仅保留一次快照", it) }
        trySend(readOutputs(audioManager, dedup))

        awaitClose {
            runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
        }
    }.distinctUntilChanged()

    /**
     * 读取输出设备；[dedup]=true 时同类型合并计数（摘要用），false 时逐设备保留
     * id/address（设备级路由选择用）。均已过滤不可播放类型并排序。
     * 异常返回空列表（UI 显示「未检测到输出设备」）。
     */
    private fun readOutputs(audioManager: AudioManager, dedup: Boolean): List<AudioOutput> =
        runCatching {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { info ->
                    AudioOutput(
                        type = info.type,
                        name = runCatching { info.productName?.toString().orEmpty() }.getOrDefault(""),
                        id = info.id,
                        address = deviceAddress(info),
                    )
                }
                .filter { isPlayableOutput(it.type) }
            if (dedup) summarizeOutputs(devices) else devices.sortedBy { it.group.ordinal }
        }.onFailure {
            Log.w(TAG, "读取输出设备失败", it)
        }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "YYXAudioOutput"

        /** [AudioDeviceInfo.getAddress] 需 API 28；26/27 返回空串（路由仅按类型匹配） */
        fun deviceAddress(info: AudioDeviceInfo): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address.orEmpty() else ""
    }
}
