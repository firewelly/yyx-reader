package com.yyx.reader.feature.reader

/** 音量键翻页：阅读器前台时接管音量键（PRD F-04-08） */
object VolumeKeyHub {
    @Volatile
    var handler: ((Int) -> Boolean)? = null
}
