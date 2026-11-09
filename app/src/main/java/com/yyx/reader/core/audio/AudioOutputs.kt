package com.yyx.reader.core.audio

import android.media.AudioDeviceInfo
import androidx.annotation.StringRes
import com.yyx.reader.R

/**
 * 听书输出设备（V1.3 F-08 输出路由增量）。
 *
 * 本轮是**诚实的降级实现**：直读模式（[android.speech.tts.TextToSpeech.speak]）由系统
 * 决定音频路由，应用只能可靠地「强制外放」（扬声器），有线/蓝牙仍跟随系统；
 * 精确指定任意设备需要 V2 的合成管线（synthesizeToFile + Media3 setPreferredAudioDevice）。
 *
 * 本文件只依赖 [AudioDeviceInfo] 的类型常量（编译期内联，不触碰 Android 运行时），
 * 因此可在 JVM 单元测试中直接覆盖。
 */

/** 输出设备分组；枚举顺序即 UI 展示顺序（labelRes → res/values/strings.xml，多语言） */
enum class AudioOutputGroup(@param:StringRes val labelRes: Int) {
    SPEAKER(R.string.audio_group_speaker),
    WIRED(R.string.audio_group_wired),
    BLUETOOTH(R.string.audio_group_bluetooth),
    USB(R.string.audio_group_usb),
    OTHER(R.string.audio_group_other),
}

/**
 * 一个输出设备（同一类型可能同时存在多个设备，展示时按类型合并：
 * [count] 为该类型的设备数量）。
 */
data class AudioOutput(
    /** [AudioDeviceInfo.getType] 返回的类型常量 */
    val type: Int,
    /** 系统上报的产品名（可能为空串，仅作调试参考；UI 以 [typeLabel] 为准） */
    val name: String = "",
    /** 同类型设备数量（去重后 ≥1） */
    val count: Int = 1,
    /**
     * [AudioDeviceInfo.getId]（V1.5 设备级路由新增；每次重启会变，不能持久化，仅运行期匹配用）。
     * 类型去重的摘要列表中无意义，保持默认 0。
     */
    val id: Int = 0,
    /** [AudioDeviceInfo.getAddress]（蓝牙 MAC / 有线端口等；空串 = 该类型无独立地址）。持久化选择器用 */
    val address: String = "",
) {
    /** 类型的展示名资源（未知类型兜底「其他音频设备」）；由界面层 stringResource 解析，支持多语言 */
    @get:StringRes
    val typeLabelRes: Int get() = outputTypeLabelRes(type)

    /** 类型所属分组 */
    val group: AudioOutputGroup get() = outputGroup(type)
}

/** 设备类型 → 展示名资源（未知类型兜底，保证 UI 永不显示空白；多语言由资源目录提供） */
@StringRes
fun outputTypeLabelRes(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.audio_speaker
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> R.string.audio_speaker_safe
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.audio_earpiece
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.audio_wired_headset
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.string.audio_wired_headphones
    AudioDeviceInfo.TYPE_LINE_ANALOG -> R.string.audio_line_analog
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> R.string.audio_line_digital
    AudioDeviceInfo.TYPE_AUX_LINE -> R.string.audio_aux_line
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.string.audio_bt_sco
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> R.string.audio_bt_a2dp
    AudioDeviceInfo.TYPE_BLE_HEADSET -> R.string.audio_ble_headset
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> R.string.audio_ble_speaker
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> R.string.audio_ble_broadcast
    AudioDeviceInfo.TYPE_HEARING_AID -> R.string.audio_hearing_aid
    AudioDeviceInfo.TYPE_USB_DEVICE -> R.string.audio_usb_device
    AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.audio_usb_headset
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.string.audio_usb_accessory
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC,
    -> R.string.audio_hdmi
    AudioDeviceInfo.TYPE_DOCK -> R.string.audio_dock
    AudioDeviceInfo.TYPE_FM -> R.string.audio_fm
    AudioDeviceInfo.TYPE_FM_TUNER -> R.string.audio_fm_tuner
    AudioDeviceInfo.TYPE_TV_TUNER -> R.string.audio_tv_tuner
    AudioDeviceInfo.TYPE_TELEPHONY -> R.string.audio_telephony
    AudioDeviceInfo.TYPE_IP -> R.string.audio_ip
    AudioDeviceInfo.TYPE_BUS -> R.string.audio_bus
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.audio_builtin_mic
    else -> R.string.audio_unknown
}

/** 设备类型 → 分组（顺序见 [AudioOutputGroup]） */
fun outputGroup(type: Int): AudioOutputGroup = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    -> AudioOutputGroup.SPEAKER

    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE,
    -> AudioOutputGroup.WIRED

    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID,
    -> AudioOutputGroup.BLUETOOTH

    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    -> AudioOutputGroup.USB

    else -> AudioOutputGroup.OTHER
}

/**
 * 该设备类型能否作为「强制外放」的落地目标：只有内置扬声器可以。
 * 有线/蓝牙设备只能跟随系统路由（直读模式无法把声音定向到它们）。
 */
fun supportsForceSpeaker(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE

/** 当前设备列表是否具备强制外放条件（存在内置扬声器） */
fun supportsForceSpeaker(devices: List<AudioOutput>): Boolean =
    devices.any { supportsForceSpeaker(it.type) }

/**
 * 该类型是否是**可播放输出**（会出现在「输出设备」摘要里）。
 *
 * 系统 `getDevices(GET_DEVICES_OUTPUTS)` 会返回通话/总线/调谐/麦克风等非媒体播放端点
 * （V1.3 截图中曾出现「扬声器 · 通话音频」），这些类型对听书无意义，UI 必须过滤。
 */
fun isPlayableOutput(type: Int): Boolean = when (type) {
    // 明确不可作为媒体播放端点的类型
    AudioDeviceInfo.TYPE_TELEPHONY,
    AudioDeviceInfo.TYPE_BUS,
    AudioDeviceInfo.TYPE_IP,
    AudioDeviceInfo.TYPE_TV_TUNER,
    AudioDeviceInfo.TYPE_FM,
    AudioDeviceInfo.TYPE_FM_TUNER,
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    AudioDeviceInfo.TYPE_UNKNOWN,
    -> false

    else -> true
}

/**
 * 去重 + 排序（纯函数，便于单测）：
 * 1) 过滤不可播放类型（[isPlayableOutput]，如通话/总线/调谐/麦克风）；
 * 2) 同类型设备合并为一条并累加 [AudioOutput.count]（保留首个产品名）；
 * 3) 按分组顺序（扬声器 → 有线 → 蓝牙 → USB → 其他）→ 中文名 → 类型值排序，保证稳定可预期。
 */
fun summarizeOutputs(devices: List<AudioOutput>): List<AudioOutput> =
    devices
        .filter { isPlayableOutput(it.type) }
        .groupBy { it.type }
        .map { (type, sameType) ->
            AudioOutput(
                type = type,
                name = sameType.firstOrNull()?.name.orEmpty(),
                count = sameType.sumOf { it.count.coerceAtLeast(1) },
            )
        }
        .sortedWith(compareBy({ it.group.ordinal }, { it.typeLabelRes }, { it.type }))

/**
 * 一行设备摘要（多语言）：[labelOf] 由界面层提供（通常为 stringResource(typeLabelRes)）。
 * 同类型多设备显示「蓝牙音频×2」；空列表返回空串（兜底文案由界面层提供 audio_none_detected）。
 */
fun outputSummary(devices: List<AudioOutput>, labelOf: (AudioOutput) -> String): String =
    devices.joinToString(" · ") { device ->
        val label = labelOf(device)
        if (device.count > 1) "$label×${device.count}" else label
    }

/**
 * 设备路由选择器（V2 精确音频路由，本地引擎专用）：持久化到 DataStore `tts_preferred_output`。
 * 格式 `t<type>`（类型唯一即可匹配，如内置扬声器）或 `t<type>:<address>`（蓝牙 MAC 等）。
 * 空串/null = 跟随系统。地址跨设备不可迁移，恢复备份后匹配不到时自然回退跟随系统。
 */
fun deviceRouteKey(type: Int, address: String): String =
    if (address.isBlank()) "t$type" else "t$type:$address"

/** 判断设备 (type, address) 是否命中选择器；选择器为空 = 跟随系统 = 不命中任何设备 */
fun matchesRouteKey(type: Int, address: String, routeKey: String?): Boolean {
    if (routeKey.isNullOrBlank()) return false
    if (!routeKey.startsWith("t")) return false
    val expectedType = routeKey.removePrefix("t").substringBefore(':').toIntOrNull() ?: return false
    if (expectedType != type) return false
    val expectedAddress = routeKey.substringAfter(':', "")
    return expectedAddress.isBlank() || expectedAddress == address
}
