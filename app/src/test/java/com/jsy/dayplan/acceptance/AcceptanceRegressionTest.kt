package com.jsy.dayplan.acceptance

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.backup.BackupCodec
import com.jsy.dayplan.backup.BackupCounts
import com.jsy.dayplan.backup.BackupIO
import com.jsy.dayplan.backup.BackupTooLargeException
import com.jsy.dayplan.backup.BackupValidator
import com.jsy.dayplan.backup.BackupV1
import com.jsy.dayplan.backup.RestorePlan
import com.jsy.dayplan.backup.ValidateResult
import com.jsy.dayplan.core.FocusTimerLogic
import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.InMemoryReminderKeyStore
import com.jsy.dayplan.reminder.ReminderCoordinator
import com.jsy.dayplan.reminder.ReminderPlans
import com.jsy.dayplan.reminder.ReminderScheduler
import com.jsy.dayplan.vm.FocusTimerViewModel
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.ThemeMode
import com.jsy.dayplan.widget.WidgetDataLoader
import com.jsy.dayplan.widget.WidgetPrefs
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * v1.0 独立复验 13 项反例的正式回归 + 生产接线集成测试。
 * 这里的断言是“应有行为”，不是对旧缺陷的复述。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DayPlanApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AcceptanceRegressionTest {

    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var app: DayPlanApp

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        repo = PlanRepository(db)
        app.getSharedPreferences("focus_timer", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanup() {
        db.close()
    }

    private fun schedule(
        id: Long = 0,
        start: String = "09:00",
        title: String = "synthetic schedule",
        date: String = "2099-11-01",
    ) = ScheduleEntity(
        id = id, title = title, startDate = date, endDate = date,
        allDay = false, startTime = start, endTime = "10:00", remindMinutesBefore = 30,
    )

    private fun countdown(
        id: Long = 0,
        remindEnabled: Boolean = true,
        remindDays: Int = 1,
        date: String = "2099-11-01",
        yearly: Boolean = false,
    ) = CountdownEntity(
        id = id, title = "synthetic date", targetDate = date, yearlyRepeat = yearly,
        remindEnabled = remindEnabled, remindDaysBefore = remindDays, remindTime = "09:00",
    )

    // ---------- A01/A02：保存返回真实 ID，提醒绑定真实记录 ----------
    @Test
    fun `A01 - 新增三类记录返回真实落库 ID`() = runBlocking {
        val s = repo.saveSchedule(schedule())
        val t = repo.saveTodo(TodoEntity(title = "synthetic todo"))
        val c = repo.saveCountdown(countdown())
        val returned = listOf(s.id, t.id, c.id)
        val actual = listOf(
            db.dao().allSchedules().single().id,
            db.dao().allTodos().single().id,
            db.dao().allCountdowns().single().id,
        )
        assertEquals("Returned IDs must match persisted rows", actual, returned)
        assertTrue(returned.all { it > 0 })
    }

    @Test
    fun `A02 - 新增待办提醒通过真实 Repository 后可解析到记录`() = runBlocking {
        val saved = repo.saveTodo(
            TodoEntity(title = "synthetic", dueDate = "2099-11-01", dueTime = "09:00"),
        )
        val scheduler = FakeReminderScheduler()
        val coordinator = ReminderCoordinator(db.dao(), scheduler)
        coordinator.onTodoSaved(saved)
        val plan = scheduler.scheduled.single()
        assertTrue("提醒 key 必须绑定真实落库 ID", plan.occurrenceKey.contains("-${saved.id}-"))
        val fired = coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs)
        assertNotNull("新增提醒必须在触发时找到其真实记录", fired)
        assertEquals("todo", fired!!.entityType)
        assertEquals(saved.id, fired.entityId)
    }

    @Test
    fun `A03 - 保存提交后调用小组件刷新回调`() = runBlocking {
        var refreshes = 0
        repo.onDataChanged = { refreshes++ }
        repo.saveTodo(TodoEntity(title = "synthetic"))
        assertEquals("Refresh callback must run after a committed save", 1, refreshes)
        repo.deleteTodo(db.dao().allTodos().single().id)
        assertEquals("Delete must also refresh widgets", 2, refreshes)
    }

    @Test
    fun `A04 - 编辑后取消实际注册的完整旧 key`() = runBlocking {
        val scheduler = FakeReminderScheduler()
        val store = InMemoryReminderKeyStore()
        val coordinator = ReminderCoordinator(db.dao(), scheduler, store)
        val old = schedule(7)
        db.dao().insertSchedule(old)
        coordinator.onScheduleSaved(old)
        val oldKey = scheduler.scheduled.single().occurrenceKey
        assertTrue(store.all().contains(oldKey))

        val updated = repo.saveSchedule(old.copy(startTime = "09:15"))
        coordinator.onScheduleSaved(updated)
        assertFalse("Full old key remains registered after edit: $oldKey", scheduler.containsKey(oldKey))
        assertEquals(1, scheduler.scheduled.size)
        assertTrue(scheduler.scheduled.single().occurrenceKey != oldKey)
    }

    // ---------- A05/A06：真实触发时刻与严格毫秒宽限 ----------
    @Test
    fun `A05 - 倒数日迟到判定使用真实提醒时刻`() = runBlocking {
        val c = countdown(id = 8, remindDays = 1)
        db.dao().insertCountdown(c)
        val plan = ReminderPlans.countdownPlan(c, LocalDate.of(2099, 10, 24))!!
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        assertNull(
            "11 minutes late must be missed",
            coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs + 11 * 60_000L),
        )
        // 同一发生再触发仍是错过（去重/错过标记已落库），不会补发
        assertNull(coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs + 60_000L))
    }

    @Test
    fun `A06 - 10 分钟整允许、加 1 秒即错过`() = runBlocking {
        val s = schedule(9)
        db.dao().insertSchedule(s)
        val plan = ReminderPlans.schedulePlan(s)!!

        val okDao = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        okDao.dao().insertSchedule(s)
        assertNotNull(
            "Exactly 10 minutes is inside the grace window",
            ReminderCoordinator(okDao.dao(), FakeReminderScheduler())
                .onFired(plan.occurrenceKey, plan.triggerAtEpochMs + 10 * 60_000L),
        )
        okDao.close()

        assertNull(
            "10 minutes plus 1 second is outside the grace window",
            ReminderCoordinator(db.dao(), FakeReminderScheduler())
                .onFired(plan.occurrenceKey, plan.triggerAtEpochMs + 10 * 60_000L + 1_000L),
        )
        assertEquals(1, db.dao().allMissed().size)
    }

    // ---------- A07：年度下一次调度 ----------
    @Test
    fun `A07 - 年度倒数日触发后注册下一次有效提醒`() = runBlocking {
        val c = countdown(id = 10, remindEnabled = true, remindDays = 1, date = "2024-11-01", yearly = true)
        db.dao().insertCountdown(c)
        val scheduler = FakeReminderScheduler()
        val coordinator = ReminderCoordinator(db.dao(), scheduler)
        val plan = ReminderPlans.countdownPlan(c, LocalDate.of(2099, 10, 24))!!
        coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs)
        assertTrue(
            "Receiver rearm path must register next year's occurrence",
            scheduler.scheduled.any { it.triggerAtEpochMs > plan.triggerAtEpochMs },
        )
    }

    @Test
    fun `倒数日 - 当天提醒与“不提醒”已分离`() = runBlocking {
        val today = LocalDate.of(2099, 11, 1)
        val onDay = countdown(id = 11, remindEnabled = true, remindDays = 0, date = "2099-11-01")
        val plan = ReminderPlans.countdownPlan(onDay, today)!!
        assertEquals("209911010900", plan.occurrenceKey.substringAfterLast("-"))
        assertNull(ReminderPlans.countdownPlan(onDay.copy(remindEnabled = false), today))
    }

    // ---------- A08：组件总数与展示行分离 ----------
    @Test
    fun `A08 - 4 个日程组件总数显示 4 且只列 3 条`() = runBlocking {
        repeat(4) { repo.saveSchedule(schedule(title = "synthetic $it")) }
        val data = WidgetDataLoader.loadToday(db, LocalDate.of(2099, 11, 1))
        assertEquals(4, data.scheduleCount)
        assertEquals(3, data.topLines.size)
    }

    // ---------- A09/A10：格式统一与合计上限 ----------
    @Test
    fun `A09 - Repository 接受的时间规范化后可通过自身备份校验`() = runBlocking {
        val saved = repo.saveSchedule(schedule(start = "9:00"))
        assertEquals("09:00", saved.startTime)
        val backup = repo.backupSnapshot("synthetic", "audit")
        val result = BackupValidator.validate(backup)
        assertTrue("Repository accepted a value its backup rejects: $result", result is ValidateResult.Ok)
    }

    @Test
    fun `A10 - 三类合计超过 10000 条拒绝导入与导出`() {
        val schedules = (1..5001).map { schedule(it.toLong()) }
        val todos = (1..5000).map { TodoEntity(id = it.toLong(), title = "synthetic $it") }
        val backup = BackupCodec.snapshot(schedules, todos, emptyList(), "synthetic", "audit")
        assertTrue(
            "10001 total records must be rejected",
            BackupValidator.validate(backup) is ValidateResult.Bad,
        )
    }

    @Test
    fun `A10b - Repository 新增在合计达到上限时拒绝但允许编辑`() = runBlocking {
        db.withTransaction {
            db.dao().insertTodosAll(
                (1L..PlanRules.MAX_ITEMS.toLong()).map { TodoEntity(id = it, title = "seed $it") },
            )
        }
        assertEquals(PlanRules.MAX_ITEMS, db.dao().totalItemCount())
        try {
            repo.saveTodo(TodoEntity(title = "one too many"))
            org.junit.Assert.fail("达到合计上限后必须拒绝新增")
        } catch (e: com.jsy.dayplan.data.PlanException) {
            assertTrue(e.message!!.contains("上限"))
        }
        // 编辑现有记录不受影响
        val first = db.dao().allTodos().first()
        val saved = repo.saveTodo(first.copy(title = "edited"))
        assertEquals(first.id, saved.id)
    }

    // ---------- A11：快照可发现 ----------
    @Test
    fun `A11 - 恢复前快照无需测试专用配置即可被设置页列举`() = runBlocking {
        repo.saveTodo(TodoEntity(title = "synthetic"))
        val dir = File(app.cacheDir, "audit_snapshot_${System.nanoTime()}")
        try {
            val file = repo.applyRestore(RestorePlan(emptyList(), emptyList(), emptyList()), dir)
            assertTrue(file.isFile)
            assertTrue(
                "Production settings must list the retained snapshot",
                repo.snapshots().any { it.canonicalPath == file.canonicalPath },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `A11b - 生产 Application 已配置快照目录`() {
        val dir = app.repo.snapshotDir
        assertNotNull("DayPlanApp must configure repo.snapshotDir", dir)
        assertTrue(dir!!.path.contains("restore_snapshots"))
    }

    @Test
    fun `A11c - 连续恢复生成排他快照文件名`() = runBlocking {
        repo.saveTodo(TodoEntity(title = "synthetic"))
        val dir = File(app.cacheDir, "snap_exclusive_${System.nanoTime()}")
        try {
            val plan = RestorePlan(emptyList(), emptyList(), emptyList())
            val f1 = repo.applyRestore(plan, dir)
            repo.saveTodo(TodoEntity(title = "again"))
            val f2 = repo.applyRestore(plan, dir)
            assertTrue(f1.name != f2.name)
            assertEquals(2, repo.snapshots().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------- A12/A13：专注计时后台闹钟与持久化 ----------
    @Test
    fun `A12 - 专注开始注册真实后台结束闹钟，暂停取消、继续重注册`() {
        val am = app.getSystemService(AlarmManager::class.java)
        val shadow = Shadows.shadowOf(am)
        val before = shadow.scheduledAlarms.size
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        assertTrue("Focus start must register AlarmManager alarm", shadow.scheduledAlarms.size > before)
        assertEquals(1, shadow.scheduledAlarms.size)
        val firstTrigger = shadow.scheduledAlarms.single().triggerAtTime
        assertTrue(firstTrigger > 0)
        val firstToken = com.jsy.dayplan.reminder.FocusAlarms.tokenOf(vm.state.value)

        vm.pause()
        assertEquals(0, shadow.scheduledAlarms.size)
        vm.resume()
        assertEquals(1, shadow.scheduledAlarms.size)
        val secondToken = com.jsy.dayplan.reminder.FocusAlarms.tokenOf(vm.state.value)
        assertTrue("每轮必须有不同身份，旧回调不能结束新一轮", secondToken != firstToken)
        vm.reset()
        assertEquals(0, shadow.scheduledAlarms.size)
    }


    @Test
    fun `专注结束 - 同一轮只允许一次结束通知`() {
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        val token = com.jsy.dayplan.reminder.FocusAlarms.tokenOf(vm.state.value)
        assertTrue("首次结束应成功", app.tryMarkFocusEnded(token))
        assertFalse("同一轮再次结束必须被拒绝", app.tryMarkFocusEnded(token))
        assertFalse(app.loadTimerState().running)
        assertTrue(app.loadTimerState().endNotified)
    }

    @Test
    fun `旧专注轮次回调不能结束新一轮`() {
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        val oldToken = com.jsy.dayplan.reminder.FocusAlarms.tokenOf(vm.state.value)
        vm.reset()
        vm.start(1)
        val newToken = com.jsy.dayplan.reminder.FocusAlarms.tokenOf(vm.state.value)
        assertTrue(oldToken != newToken)
        assertFalse(app.tryMarkFocusEnded(oldToken))
        assertTrue(app.loadTimerState().running)
    }

    @Test
    fun `A13 - 重启所需剩余时长经过实际序列化保留`() {
        val started = FocusTimerLogic.start(25, 1000, "synthetic-boot")
        app.saveTimerState(started)
        assertEquals(
            "lastKnownRemainingMs must survive real SharedPreferences serialization",
            started.lastKnownRemainingMs,
            app.loadTimerState().lastKnownRemainingMs,
        )
    }

    // ---------- 补充集成回归 ----------
    @Test
    fun `删除与完成会取消完整提醒身份，撤销完成重新注册`() = runBlocking {
        val scheduler = FakeReminderScheduler()
        val store = InMemoryReminderKeyStore()
        val coordinator = ReminderCoordinator(db.dao(), scheduler, store)
        val saved = repo.saveTodo(
            TodoEntity(title = "交报告", dueDate = "2099-11-01", dueTime = "09:00"),
        )
        coordinator.onTodoSaved(saved)
        assertEquals(1, scheduler.scheduled.size)

        val done = repo.saveTodo(saved.copy(done = true))
        coordinator.onTodoSaved(done)
        assertEquals(0, scheduler.scheduled.size)

        val undone = repo.saveTodo(done.copy(done = false))
        coordinator.onTodoSaved(undone)
        assertEquals(1, scheduler.scheduled.size)

        val key = scheduler.scheduled.single().occurrenceKey
        repo.deleteTodo(undone.id)
        coordinator.onTodoDeleted(undone.id, undone.version)
        assertEquals(0, scheduler.scheduled.size)
        assertFalse(scheduler.containsKey(key))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `并发重复触发只产生一次通知`() = runBlocking {
        val saved = repo.saveTodo(
            TodoEntity(title = "并发", dueDate = "2099-11-01", dueTime = "09:00"),
        )
        val scheduler = FakeReminderScheduler()
        val coordinator = ReminderCoordinator(db.dao(), scheduler)
        coordinator.onTodoSaved(saved)
        val plan = scheduler.scheduled.single()
        val results = coroutineScope {
            (1..8).map {
                async(Dispatchers.IO) {
                    coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs)
                }
            }.awaitAll()
        }
        assertEquals(1, results.count { it != null })
    }

    @Test
    fun `有界读取超过 10MiB 立即拒绝`() {
        val oversized = object : InputStream() {
            var produced = 0L
            override fun read(): Int {
                if (produced >= PlanRules.MAX_JSON_BYTES + 1L) return -1
                produced++
                return 'a'.code
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (produced >= PlanRules.MAX_JSON_BYTES + 1L) return -1
                val n = minOf(len.toLong(), PlanRules.MAX_JSON_BYTES + 1L - produced).toInt()
                java.util.Arrays.fill(b, off, off + n, 'a'.code.toByte())
                produced += n
                return n
            }
        }
        try {
            BackupIO.readTextBounded(oversized)
            org.junit.Assert.fail("超过 10 MiB 的文件必须被拒绝")
        } catch (e: BackupTooLargeException) {
            assertTrue(oversized.produced <= PlanRules.MAX_JSON_BYTES + 1L)
        }
    }

    @Test
    fun `v1 备份倒数日提醒无损升级为显式开关`() {
        val v1 = BackupV1(
            schemaVersion = 1,
            exportedAt = "t",
            appVersion = "1.0",
            counts = BackupCounts(0, 0, 1),
            schedules = emptyList(),
            todos = emptyList(),
            countdowns = listOf(
                com.jsy.dayplan.backup.BackupCountdown(
                    id = 1, title = "生日", targetDate = "2024-11-01", category = 2,
                    note = "", colorArgb = 0, pinned = false, yearlyRepeat = true,
                    remindDaysBefore = 7, remindTime = "9:00", originalDate = "2024-11-01",
                    version = 1,
                ),
            ),
        )
        val r = BackupValidator.validate(v1)
        assertTrue(r is ValidateResult.Ok)
        val ok = r as ValidateResult.Ok
        assertEquals(2, ok.backup.schemaVersion)
        assertTrue(ok.backup.countdowns.single().remindEnabled)
        assertEquals("09:00", ok.backup.countdowns.single().remindTime)
    }

    @Test
    fun `恢复事务成功后即使提醒重建失败数据仍为已恢复状态`() = runBlocking {
        val target = PlanRepository(db)
        target.saveSchedule(schedule(title = "restore target", date = "2099-11-01"))
        target.saveTodo(TodoEntity(title = "restore todo"))
        val backup = target.backupSnapshot("t", "1.1")

        val failingScheduler = object : ReminderScheduler {
            override fun canScheduleExact(): Boolean = true
            override fun schedule(plan: ReminderPlans.ReminderPlan, exactAllowed: Boolean) {
                throw IllegalStateException("synthetic scheduler failure")
            }
            override fun cancel(occurrenceKey: String) = Unit
            override fun cancelAll(keys: List<String>) = Unit
        }
        val failing = ReminderCoordinator(db.dao(), failingScheduler)
        val dir = File(app.cacheDir, "snap_phase_${System.nanoTime()}")
        try {
            repo.applyRestore(BackupCodec.toEntities(backup), dir)
            val rebuild = runCatching { failing.rebuildAll(java.time.LocalDateTime.now()) }
            assertTrue(rebuild.isFailure)
            // 数据已替换：界面必须能区分“已恢复但提醒待重建”
            assertEquals(listOf("restore target"), repo.loadAll().schedules.map { it.title })
            assertTrue(repo.snapshots().isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }


    @Test
    fun `重建 - 宽限内补发、超过宽限留下错过状态`() = runBlocking {
        val c = countdown(id = 30, remindEnabled = true, remindDays = 1, date = "2099-11-01")
        db.dao().insertCountdown(c)
        val plan = ReminderPlans.countdownPlan(c, LocalDate.of(2099, 10, 24))!!

        val inGrace = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        val nowGrace = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(plan.triggerAtEpochMs + 5 * 60_000L),
            java.time.ZoneId.systemDefault(),
        )
        val delivered = inGrace.rebuildAll(nowGrace)
        assertEquals(1, delivered.size)

        val lateDao = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        lateDao.dao().insertCountdown(c)
        val late = ReminderCoordinator(lateDao.dao(), FakeReminderScheduler())
        val nowLate = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(plan.triggerAtEpochMs + 11 * 60_000L),
            java.time.ZoneId.systemDefault(),
        )
        assertTrue(late.rebuildAll(nowLate).isEmpty())
        assertEquals(1, lateDao.dao().allMissed().size)
        lateDao.close()
    }

    @Test
    fun `数据库迁移 v1 到 v2 - 旧 1 和 7 天升级为启用，旧 0 保持关闭`() {
        val name = "migration_${System.nanoTime()}.db"
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE countdowns (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, " +
                            "targetDate TEXT NOT NULL, category INTEGER NOT NULL, note TEXT NOT NULL, " +
                            "colorArgb INTEGER NOT NULL, pinned INTEGER NOT NULL, " +
                            "yearlyRepeat INTEGER NOT NULL, remindDaysBefore INTEGER NOT NULL, " +
                            "remindTime TEXT NOT NULL, originalDate TEXT NOT NULL, version INTEGER NOT NULL)",
                    )
                }
                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(config)
        val sql = helper.writableDatabase
        try {
            sql.execSQL(
                "INSERT INTO countdowns (id,title,targetDate,category,note,colorArgb,pinned," +
                    "yearlyRepeat,remindDaysBefore,remindTime,originalDate,version) VALUES " +
                    "(1,'a','2099-01-01',0,'',0,0,0,7,'09:00','',1)," +
                    "(2,'b','2099-01-01',0,'',0,0,0,0,'09:00','',1)",
            )
            com.jsy.dayplan.data.PlanDb.MIGRATION_1_2.migrate(sql)
            sql.query("SELECT id, remindEnabled, remindDaysBefore FROM countdowns ORDER BY id").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(1L, cur.getLong(0))
                assertEquals(1, cur.getInt(1))
                assertEquals(7, cur.getInt(2))
                assertTrue(cur.moveToNext())
                assertEquals(2L, cur.getLong(0))
                assertEquals(0, cur.getInt(1))
                assertEquals(0, cur.getInt(2))
            }
        } finally {
            helper.close()
            app.deleteDatabase(name)
        }
    }


    @Test
    fun `小组件多实例绑定互不影响`() {
        WidgetPrefs.bindCountdown(app, 101, 1L)
        WidgetPrefs.bindCountdown(app, 102, 2L)
        assertEquals(1L, WidgetPrefs.getCountdownBinding(app, 101))
        assertEquals(2L, WidgetPrefs.getCountdownBinding(app, 102))
        WidgetPrefs.removeCountdown(app, 101)
        assertEquals(-1L, WidgetPrefs.getCountdownBinding(app, 101))
        assertEquals(2L, WidgetPrefs.getCountdownBinding(app, 102))
    }

    @Test
    fun `主题按钮保存后新 ViewModel 仍读取同一主题`() {
        val vm1 = PlanViewModel(app)
        vm1.setThemeMode(ThemeMode.DARK)
        val vm2 = PlanViewModel(app)
        assertEquals(ThemeMode.DARK, vm2.themeMode.value)
        vm2.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, PlanViewModel(app).themeMode.value)
    }
}
