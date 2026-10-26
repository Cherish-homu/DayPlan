package com.jsy.dayplan.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 专注外观/音效偏好：默认值、持久化、越界收敛。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusPreferencesTest {

    private lateinit var context: Context
    private lateinit var prefs: FocusPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("focus_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = FocusPreferences(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("focus_appearance", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `新安装默认值`() {
        val value = prefs.load()
        assertEquals(3, value.scene)
        assertEquals(3f, value.blur, 0.001f)
        assertEquals(0.55f, value.scrim, 0.001f)
        assertEquals(FocusSound.OFF, value.sound)
        assertEquals(0.5f, value.volume, 0.001f)
        assertEquals(25, value.minutes)
    }

    @Test
    fun `保存后重新读取保持一致`() {
        prefs.save(
            FocusAppearance(
                scene = 1, blur = 7.5f, scrim = 0.8f,
                sound = FocusSound.WAVES, volume = 0.2f, minutes = 45,
            ),
        )
        val reloaded = FocusPreferences(context).load()
        assertEquals(1, reloaded.scene)
        assertEquals(7.5f, reloaded.blur, 0.001f)
        assertEquals(0.8f, reloaded.scrim, 0.001f)
        assertEquals(FocusSound.WAVES, reloaded.sound)
        assertEquals(0.2f, reloaded.volume, 0.001f)
        assertEquals(45, reloaded.minutes)
    }

    @Test
    fun `越界值保存与读取都会收敛`() {
        prefs.save(
            FocusAppearance(
                scene = 99, blur = 100f, scrim = -1f,
                sound = FocusSound.RAIN, volume = 5f, minutes = 999,
            ),
        )
        val loaded = prefs.load()
        assertEquals(4, loaded.scene)
        assertEquals(12f, loaded.blur, 0.001f)
        assertEquals(0.15f, loaded.scrim, 0.001f)
        assertEquals(1f, loaded.volume, 0.001f)
        assertEquals(180, loaded.minutes)
    }

    @Test
    fun `未知音效名回落到关闭`() {
        context.getSharedPreferences("focus_appearance", Context.MODE_PRIVATE)
            .edit().putString("sound", "THUNDER").commit()
        assertEquals(FocusSound.OFF, prefs.load().sound)
    }
}
