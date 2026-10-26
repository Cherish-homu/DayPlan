package com.jsy.dayplan.core

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 白噪音合成：关闭为静音，其余有界、非全零、开头淡入，且同种子可复现。 */
class FocusNoiseTest {

    private fun render(sound: FocusSound, samples: Int = 22050, seed: Int = 7): ShortArray =
        ShortArray(samples).also { FocusNoise(sound, seed).fill(it, sampleRate = 22050) }

    @Test
    fun `关闭音效输出全零`() {
        val buffer = render(FocusSound.OFF, samples = 4096)
        assertTrue(buffer.all { it.toInt() == 0 })
    }

    @Test
    fun `三种音效都有声音且不削顶`() {
        val limit = (0.85 * Short.MAX_VALUE).toInt()
        listOf(FocusSound.WHITE, FocusSound.RAIN, FocusSound.WAVES).forEach { sound ->
            val buffer = render(sound)
            assertTrue("$sound 应产生非零采样", buffer.any { it.toInt() != 0 })
            val peak = buffer.maxOf { abs(it.toInt()) }
            assertTrue("$sound 峰值 $peak 超出安全范围", peak in 1..limit)
        }
    }

    @Test
    fun `开头淡入避免爆音`() {
        val buffer = render(FocusSound.WAVES)
        assertEquals(0, buffer[0].toInt())
        assertTrue("前 10ms 应仍接近静音", buffer.take(220).all { abs(it.toInt()) < 3000 })
        assertTrue("1 秒后应有明显输出", buffer.drop(22050 / 2).any { abs(it.toInt()) > 1000 })
    }

    @Test
    fun `海浪是连续低频而非逐样本跳变`() {
        val buffer = render(FocusSound.WAVES)
        var maxDelta = 0
        for (i in 1 until buffer.size) {
            maxDelta = maxOf(maxDelta, abs(buffer[i] - buffer[i - 1]).toInt())
        }
        assertTrue("海浪相邻样本跳变过大：$maxDelta", maxDelta < 4000)
    }

    @Test
    fun `相同种子输出可复现`() {
        assertArrayEquals(render(FocusSound.RAIN, seed = 99), render(FocusSound.RAIN, seed = 99))
    }
}
