package com.jsy.dayplan.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 本地连续合成，无采样文件或网络。
 * 每类音效都经过滤波和慢速包络，避免短音频循环接缝；开头 0.4 秒淡入。
 */
class FocusNoise(private val sound: FocusSound, seed: Int = 42) {
    private val random = Random(seed)
    private var low = 0.0
    private var time = 0L

    fun fill(buffer: ShortArray, sampleRate: Int = 22050) {
        for (i in buffer.indices) {
            val white = random.nextDouble(-1.0, 1.0)
            low += 0.035 * (white - low)
            val seconds = time++.toDouble() / sampleRate
            val sample = when (sound) {
                FocusSound.OFF -> 0.0
                FocusSound.WHITE -> white * 0.34
                // 细雨：高通残留的白噪声为主，叠一层低频雨幕
                FocusSound.RAIN ->
                    ((white - low) * 0.55 + low * 1.4) * (0.75 + 0.12 * sin(seconds * 0.7))
                // 海浪：低频起伏 + 8.5 秒慢包络，形成一阵阵推进感
                FocusSound.WAVES ->
                    low * 4.2 * (0.42 + 0.42 * sin(2 * PI * seconds / 8.5))
            }
            val fade = (seconds / 0.4).coerceIn(0.0, 1.0)
            buffer[i] = (sample.coerceIn(-0.85, 0.85) * fade * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
