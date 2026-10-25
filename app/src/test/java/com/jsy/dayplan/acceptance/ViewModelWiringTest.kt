package com.jsy.dayplan.acceptance

import android.app.AlarmManager
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.reminder.AndroidReminderScheduler
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.InMemoryReminderKeyStore
import com.jsy.dayplan.reminder.ReminderCoordinator
import com.jsy.dayplan.vm.PlanViewModel
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 生产接线：PlanViewModel → Repository → Coordinator → 生产 AlarmManager。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DayPlanApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ViewModelWiringTest {

    @Test
    fun `ViewModel 保存使用真实落库结果并经真实调度器注册提醒`() {
        val app: DayPlanApp = ApplicationProvider.getApplicationContext()
        // 内存数据库 + 生产 AndroidReminderScheduler，避免共享文件库影响测试隔离
        val db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        val repo = PlanRepository(db)
        val coordinator = ReminderCoordinator(
            db.dao(), AndroidReminderScheduler(app), InMemoryReminderKeyStore(),
        )
        val vm = PlanViewModel(app, repo, coordinator)
        val am = app.getSystemService(AlarmManager::class.java)
        val shadow = Shadows.shadowOf(am)
        val before = shadow.scheduledAlarms.size
        var done = false
        vm.saveTodo(
            TodoEntity(
                title = "vm-wired",
                dueDate = LocalDate.now().plusDays(1).toString(),
                dueTime = "09:00",
            ),
        ) { done = true }

        val main = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 8_000
        while (!done && System.currentTimeMillis() < deadline) {
            main.idle()
            Thread.sleep(50)
        }
        main.idle()

        assertTrue("保存回调应执行 (saving=${vm.saving.value}, err=${vm.saveError.value})", done)
        assertTrue("ViewModel→Coordinator→AlarmManager 应注册提醒", shadow.scheduledAlarms.size > before)
        val row = runBlocking { db.dao().allTodos().lastOrNull { it.title == "vm-wired" } }
        assertNotNull(row)
        assertTrue("返回实体必须带真实落库 ID", row!!.id > 0)
        db.close()
    }

    @Test
    fun `冷加载 - 数据 Flow 首次到达后编辑页所需实体可用`() {
        val app: DayPlanApp = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        val repo = PlanRepository(db)
        val saved = runBlocking {
            repo.saveTodo(TodoEntity(title = "cold-load", dueDate = null))
        }
        val vm = PlanViewModel(
            app, repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()),
        )
        val main = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 8_000
        while (!vm.todosLoaded.value && System.currentTimeMillis() < deadline) {
            main.idle()
            Thread.sleep(20)
        }
        main.idle()
        assertTrue("首次 Flow 到达后 loaded 标志必须为真", vm.todosLoaded.value)
        val loaded = vm.todos.value.firstOrNull { it.id == saved.id }
        assertNotNull("编辑页打开时实体已经可用，不需要 existing!! 空指针路径", loaded)
        db.close()
    }
}
