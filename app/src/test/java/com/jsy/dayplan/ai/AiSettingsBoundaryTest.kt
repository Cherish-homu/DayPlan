package com.jsy.dayplan.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 首次读取会话 ID 的并发安全：reload 与发送同时进行也必须得到同一边界。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSettingsBoundaryTest {

    @Test
    fun `并发首次读取只生成一个会话`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.nanoTime().toString()
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$name-$suffix", mode)
        }
        val store = AiSettingsStore(context)
        val other = AiSettingsStore(context)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val ids = ConcurrentHashMap.newKeySet<String>()
            val tasks = (1..64).map { index ->
                pool.submit {
                    start.await()
                    ids.add(if (index % 2 == 0) store.chatSessionId else other.chatSessionId)
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals("并发首次访问必须只落一个会话 ID", 1, ids.size)
            assertEquals(ids.single(), store.chatSessionId)
        } finally {
            pool.shutdownNow()
        }
    }
}
