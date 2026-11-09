package com.yyx.reader.core.ebook.txt

import org.mozilla.universalchardet.UniversalDetector
import java.nio.charset.Charset

/**
 * TXT 编码探测：BOM → juniversalchardet → 中文语境回退链。
 * 移植自 novelmgt 的 UTF-8 → GBK/GB18030 → Big5 策略。
 */
object EncodingDetector {

    const val FALLBACK = "GB18030"

    fun detect(bytes: ByteArray): String {
        // 1. BOM 优先
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return "UTF-8"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"

        // 2. juniversalchardet（Mozilla universalchardet 的 Java 移植）
        val detector = UniversalDetector(null)
        val sampleLen = minOf(bytes.size, 64 * 1024)
        detector.handleData(bytes, 0, sampleLen)
        detector.dataEnd()
        val detected = detector.detectedCharset
        detector.reset()

        return normalize(detected)
    }

    /** 探测结果归一化；无法识别时回退 GB18030（中文语境最优兜底） */
    fun normalize(name: String?): String = when (name?.uppercase()) {
        "UTF-8" -> "UTF-8"
        "UTF-16LE", "UTF-16" -> "UTF-16LE"
        "UTF-16BE" -> "UTF-16BE"
        "GB18030", "GBK", "GB2312", "WINDOWS-936", "EUC-CN", "GB_2312-80", "ISO-2022-CN" -> "GB18030"
        "BIG5", "BIG5-HKSCS", "WINDOWS-950", "CP950", "X-X-BIG5" -> "Big5"
        else -> FALLBACK
    }

    fun charsetFor(name: String): Charset =
        try {
            Charset.forName(name)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
}
