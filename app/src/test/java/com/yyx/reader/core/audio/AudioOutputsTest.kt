package com.yyx.reader.core.audio

import android.media.AudioDeviceInfo
import com.yyx.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1.3 F-08 输出路由纯函数单测：类型映射 / 分组 / 排序 / 去重计数 / 未知类型兜底 / 空列表 */
class AudioOutputsTest {

    private fun device(type: Int, name: String = "") = AudioOutput(type = type, name = name)

    // ---------- 1. 类型映射 ----------

    @Test
    fun `设备类型映射到字符串资源`() {
        assertEquals(R.string.audio_speaker, outputTypeLabelRes(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(R.string.audio_wired_headphones, outputTypeLabelRes(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(R.string.audio_wired_headset, outputTypeLabelRes(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(R.string.audio_bt_a2dp, outputTypeLabelRes(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(R.string.audio_ble_headset, outputTypeLabelRes(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(R.string.audio_usb_headset, outputTypeLabelRes(AudioDeviceInfo.TYPE_USB_HEADSET))
        // 派生属性与纯函数一致（UI 读 typeLabelRes 再 stringResource）
        assertEquals(R.string.audio_bt_a2dp, device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).typeLabelRes)
    }

    @Test
    fun `未知类型兜底为其他音频设备`() {
        assertEquals(R.string.audio_unknown, outputTypeLabelRes(Int.MAX_VALUE))
        assertEquals(R.string.audio_unknown, outputTypeLabelRes(-1))
        assertEquals(AudioOutputGroup.OTHER, outputGroup(Int.MAX_VALUE))
        assertEquals(R.string.audio_unknown, device(98765).typeLabelRes)
    }

    // ---------- 2. 分组 ----------

    @Test
    fun `设备类型映射到四个分组`() {
        assertEquals(AudioOutputGroup.SPEAKER, outputGroup(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(AudioOutputGroup.SPEAKER, outputGroup(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
        assertEquals(AudioOutputGroup.WIRED, outputGroup(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(AudioOutputGroup.WIRED, outputGroup(AudioDeviceInfo.TYPE_AUX_LINE))
        assertEquals(AudioOutputGroup.BLUETOOTH, outputGroup(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioOutputGroup.BLUETOOTH, outputGroup(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(AudioOutputGroup.USB, outputGroup(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(AudioOutputGroup.USB, outputGroup(AudioDeviceInfo.TYPE_USB_HEADSET))
        // 听筒不属于扬声器组：不能被「强制外放」当成外放目标
        assertEquals(AudioOutputGroup.OTHER, outputGroup(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
    }

    // ---------- 3. 排序：扬声器 → 有线 → 蓝牙 → USB → 其他 ----------

    @Test
    fun `乱序设备按分组顺序排序`() {
        val shuffled = listOf(
            device(AudioDeviceInfo.TYPE_USB_HEADSET),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
            device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
            device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        val summary = summarizeOutputs(shuffled)
        assertEquals(
            listOf(
                R.string.audio_speaker,
                R.string.audio_wired_headphones,
                R.string.audio_bt_a2dp,
                R.string.audio_usb_headset,
                R.string.audio_earpiece,
            ),
            summary.map { it.typeLabelRes },
        )
        assertEquals(
            listOf(
                AudioOutputGroup.SPEAKER,
                AudioOutputGroup.WIRED,
                AudioOutputGroup.BLUETOOTH,
                AudioOutputGroup.USB,
                AudioOutputGroup.OTHER,
            ),
            summary.map { it.group },
        )
    }

    // ---------- 4. 去重 + 计数 ----------

    @Test
    fun `同类型多设备合并为一条并累计数量`() {
        val devices = listOf(
            device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "SoundCore"),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds 2"),
        )
        val summary = summarizeOutputs(devices)
        assertEquals(2, summary.size)
        assertEquals(R.string.audio_speaker, summary[0].typeLabelRes)
        assertEquals(1, summary[0].count)
        assertEquals(R.string.audio_bt_a2dp, summary[1].typeLabelRes)
        assertEquals(3, summary[1].count)
        // 代表项保留首个设备名（仅调试参考，UI 用类型资源名）
        assertEquals("SoundCore", summary[1].name)
        // 摘要文案带数量后缀（标签由调用方解析，这里用测试占位标签）
        assertEquals("L${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER} · L${AudioDeviceInfo.TYPE_BLUETOOTH_A2DP}×3", outputSummary(summary) { "L${it.type}" })
    }

    // ---------- 5. supportsForceSpeaker 判定 ----------

    @Test
    fun `仅内置扬声器可作为强制外放目标`() {
        assertTrue(supportsForceSpeaker(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(supportsForceSpeaker(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
        assertFalse(supportsForceSpeaker(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertFalse(supportsForceSpeaker(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(supportsForceSpeaker(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertFalse(supportsForceSpeaker(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))

        // 列表级判定：有扬声器才可强制外放
        assertTrue(supportsForceSpeaker(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))))
        assertFalse(supportsForceSpeaker(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))))
        assertFalse(supportsForceSpeaker(emptyList()))
    }

    // ---------- 6. 空列表 ----------

    @Test
    fun `空列表不崩溃且摘要为空串`() {
        assertEquals(emptyList<AudioOutput>(), summarizeOutputs(emptyList()))
        assertEquals("", outputSummary(emptyList()) { "L${it.type}" })
        assertEquals("", outputSummary(summarizeOutputs(emptyList())) { "L${it.type}" })
        // 兜底文案（audio_none_detected）由界面层在空列表时提供
    }

    @Test
    fun `单设备摘要不带数量后缀`() {
        val summary = summarizeOutputs(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertEquals("L${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}", outputSummary(summary) { "L${it.type}" })
    }

    // ---------- 7. 不可播放类型过滤（V1.3 收尾 P2-5） ----------

    @Test
    fun `不可播放类型不进入设备摘要`() {
        val devices = listOf(
            device(AudioDeviceInfo.TYPE_TELEPHONY),
            device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            device(AudioDeviceInfo.TYPE_BUILTIN_MIC),
            device(AudioDeviceInfo.TYPE_BUS),
        )
        val summary = summarizeOutputs(devices)
        assertEquals(1, summary.size)
        assertEquals("L${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}", outputSummary(summary) { "L${it.type}" })
    }

    @Test
    fun `调谐与麦克风等类型全部过滤后为空`() {
        val devices = listOf(
            device(AudioDeviceInfo.TYPE_TV_TUNER),
            device(AudioDeviceInfo.TYPE_FM),
            device(AudioDeviceInfo.TYPE_FM_TUNER),
            device(AudioDeviceInfo.TYPE_BUILTIN_MIC),
            device(AudioDeviceInfo.TYPE_IP),
        )
        assertTrue(summarizeOutputs(devices).isEmpty())
        assertEquals("", outputSummary(summarizeOutputs(devices)) { "L${it.type}" })
    }

    @Test
    fun `isPlayableOutput 判定`() {
        assertTrue(isPlayableOutput(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(isPlayableOutput(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertTrue(isPlayableOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(isPlayableOutput(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertTrue(isPlayableOutput(AudioDeviceInfo.TYPE_HDMI))
        assertFalse(isPlayableOutput(AudioDeviceInfo.TYPE_TELEPHONY))
        assertFalse(isPlayableOutput(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(isPlayableOutput(AudioDeviceInfo.TYPE_BUS))
        assertFalse(isPlayableOutput(AudioDeviceInfo.TYPE_TV_TUNER))
        assertFalse(isPlayableOutput(AudioDeviceInfo.TYPE_FM))
        assertFalse(isPlayableOutput(AudioDeviceInfo.TYPE_UNKNOWN))
    }

    @Test
    fun `路由选择器-类型唯一设备生成无地址键`() {
        // 用常量插值构造期望值，避免硬编码类型数字（TYPE_BUILTIN_SPEAKER=2）
        assertEquals("t${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}", deviceRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""))
        assertEquals("t${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}", deviceRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "  "))
    }

    @Test
    fun `路由选择器-带地址设备生成 t类型与地址`() {
        assertEquals("t8:AA:BB:CC:DD:EE", deviceRouteKey(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB:CC:DD:EE"))
    }

    @Test
    fun `路由选择器匹配-类型命中且无地址要求`() {
        val speakerKey = "t${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}"
        assertTrue(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", speakerKey))
        // 类型命中但持久化键带地址时，地址必须一致
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "${speakerKey}:AA"))
        assertTrue(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "AA", "${speakerKey}:AA"))
    }

    @Test
    fun `路由选择器匹配-蓝牙地址区分不同设备`() {
        val key = deviceRouteKey(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:AA")
        assertTrue(matchesRouteKey(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:AA", key))
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "BB:BB", key))
        // 类型不同即使地址相同也不命中
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BLE_HEADSET, "AA:AA", key))
    }

    @Test
    fun `路由选择器匹配-空键与畸形键一律不命中`() {
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", null))
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", ""))
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "  "))
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "x9"))
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "t:"))
        assertFalse(matchesRouteKey(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "tnotanumber"))
    }
}
