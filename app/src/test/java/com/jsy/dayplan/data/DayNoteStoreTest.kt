package com.jsy.dayplan.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 月历便签：按日期存取、空白删除、非法日期拒绝、重开持久化。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayNoteStoreTest {
    private lateinit var context: Context
    private lateinit var store: DayNoteStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = DayNoteStore(context, null)
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun `保存后按日期读取`() {
        val saved = store.save("2026-10-26", "今天读完第三章\n晚上跑步")!!
        assertEquals("2026-10-26", saved.date)
        assertEquals("今天读完第三章\n晚上跑步", saved.text)
        assertEquals(saved, store.load("2026-10-26"))
        assertNull(store.load("2026-10-27"))
    }

    @Test
    fun `再次保存覆盖旧内容并更新时间`() {
        val first = store.save("2026-10-26", "第一版")!!
        Thread.sleep(2)
        val second = store.save("2026-10-26", "第二版")!!
        assertEquals("第二版", store.load("2026-10-26")!!.text)
        assertTrue(second.updatedAt >= first.updatedAt)
        assertEquals(2L, store.revision.value)
    }

    @Test
    fun `空白内容删除当天便签`() {
        store.save("2026-10-26", "写点什么")
        assertNull(store.save("2026-10-26", "   \n  "))
        assertNull(store.load("2026-10-26"))
        assertEquals(2L, store.revision.value)
    }

    @Test
    fun `非法日期与超长内容被拒绝`() {
        listOf("2026-13-40", "2026/10/26", "20261026", "").forEach { bad ->
            try {
                store.save(bad, "x")
                org.junit.Assert.fail("非法日期应被拒绝：$bad")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
        try {
            store.save("2026-10-26", "x".repeat(DayNoteStore.MAX_CHARS + 1))
            org.junit.Assert.fail("超长便签应被拒绝")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `关闭重开后便签仍在`() {
        val name = "notes_test_${System.nanoTime()}.db"
        try {
            val first = DayNoteStore(context, name)
            first.save("2026-12-31", "跨年计划")
            first.close()

            val second = DayNoteStore(context, name)
            assertEquals("跨年计划", second.load("2026-12-31")!!.text)
            second.close()
        } finally {
            context.deleteDatabase(name)
        }
    }
}
