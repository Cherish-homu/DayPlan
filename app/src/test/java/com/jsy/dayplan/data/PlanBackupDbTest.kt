package com.jsy.dayplan.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.backup.BackupCodec
import com.jsy.dayplan.backup.BackupCounts
import com.jsy.dayplan.backup.BackupV1
import com.jsy.dayplan.backup.BackupValidator
import com.jsy.dayplan.backup.ValidateResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room 真库（Robolectric）：正常保存 → 一致性事务快照 → JSON 回读 → 恢复到另一个库 →
 * 重新查询比对；坏 JSON、重复 ID、超长字段、非法日期、快照不可写、各阶段注入异常
 * 均应拒绝或完整回滚，原数据不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanBackupDbTest {

    private lateinit var dbA: PlanDb
    private lateinit var dbB: PlanDb
    private lateinit var repoA: PlanRepository
    private lateinit var repoB: PlanRepository
    private lateinit var snapDir: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbA = Room.inMemoryDatabaseBuilder(ctx, PlanDb::class.java)
            .allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(ctx, PlanDb::class.java)
            .allowMainThreadQueries().build()
        repoA = PlanRepository(dbA)
        repoB = PlanRepository(dbB)
        snapDir = File(ctx.cacheDir, "dayplan_snaps_" + System.nanoTime())
        repoB.snapshotDir = snapDir
    }

    @After
    fun tearDown() {
        dbA.close(); dbB.close()
        snapDir.deleteRecursively()
    }

    private suspend fun seedLedger(repo: PlanRepository) {
        val sch1 = repo.saveSchedule(
            ScheduleEntity(
                title = "项目例会", startDate = "2026-10-26", endDate = "2026-10-26",
                allDay = false, startTime = "09:00", endTime = "10:30",
                location = "会议室A", note = "", category = 1, remindMinutesBefore = 30,
            ),
        )
        // 跨日日程（22:00 → 次日 00:00 左闭右开）
        repo.saveSchedule(
            ScheduleEntity(
                title = "跨年夜守岁", startDate = "2026-12-31", endDate = "2027-01-01",
                allDay = false, startTime = "22:00", endTime = "00:00",
                location = "", note = "跨日", category = 2, remindMinutesBefore = 0,
            ),
        )
        repo.saveTodo(
            TodoEntity(
                title = "交报告", dueDate = "2026-10-26", dueTime = "18:00",
                priority = 1, note = "季度报告",
            ),
        )
        repo.saveTodo(TodoEntity(title = "无日期待办", priority = 0))
        repo.saveCountdown(
            CountdownEntity(
                title = "项目上线", targetDate = "2026-11-15", category = 1,
                pinned = true, yearlyRepeat = false, remindDaysBefore = 7,
                originalDate = "2026-11-15",
            ),
        )
        repo.saveCountdown(
            CountdownEntity(
                title = "生日", targetDate = "2024-02-29", category = 2,
                yearlyRepeat = true, remindDaysBefore = 1, originalDate = "2024-02-29",
            ),
        )
        sch1; repo.loadAll()
    }

    private suspend fun countOf(repo: PlanRepository): Triple<Int, Int, Int> {
        val all = repo.loadAll()
        return Triple(all.schedules.size, all.todos.size, all.countdowns.size)
    }

    @Test
    fun `正常保存 - 边界数据均可通过自身备份校验`() = runBlocking {
        seedLedger(repoA)
        val backup = kotlinx.coroutines.runBlocking { repoA.backupSnapshot("t", "1.0") }
        when (val v = BackupValidator.validate(backup)) {
            is ValidateResult.Bad -> fail("正常账本备份应通过校验：" + v.errors.first())
            is ValidateResult.Ok -> {
                assertEquals(2, v.preview.schedules)
                assertEquals(2, v.preview.todos)
                assertEquals(2, v.preview.countdowns)
            }
        }
    }

    @Test
    fun `恢复1 - 备份恢复到另一个库 逐字段与数量一致且不叠加`() = runBlocking {
        seedLedger(repoA)
        val backup = repoA.backupSnapshot("t", "1.0")
        repoB.applyRestore(BackupCodec.toEntities(backup), snapDir)

        // 重新查询 DAO 逐字段核对
        val aAll = repoA.loadAll()
        val bAll = repoB.loadAll()
        assertEquals(aAll.schedules, bAll.schedules)
        assertEquals(aAll.todos, bAll.todos)
        assertEquals(aAll.countdowns, bAll.countdowns)

        // 跨日与完成状态/置顶保留
        val cross = bAll.schedules.first { it.title == "跨年夜守岁" }
        assertEquals("2026-12-31", cross.startDate)
        assertEquals("2027-01-01", cross.endDate)
        assertTrue(bAll.countdowns.first { it.title == "项目上线" }.pinned)

        // 再次恢复 = 替换，不叠加
        repoB.applyRestore(BackupCodec.toEntities(backup), snapDir)
        assertEquals(Triple(2, 2, 2), countOf(repoB))
    }

    @Test
    fun `恢复2 - 清空后与各插入阶段注入异常均完整回滚`() = runBlocking {
        seedLedger(repoB)
        val before = repoB.loadAll()
        val backup = repoA.backupSnapshot("t", "1.0")
        val plan = BackupCodec.toEntities(backup)

        for (stage in 0..3) {
            try {
                repoB.applyRestore(plan, snapDir, failAfter = stage)
                fail("阶段 $stage 应当注入失败")
            } catch (e: PlanException) {
                assertTrue(e.message!!.contains("回滚"))
            }
            assertEquals(before, repoB.loadAll())
        }
        // 快照文件已保留（回退入口）
        assertTrue(repoB.snapshots().isNotEmpty())
    }

    @Test
    fun `恢复3 - 快照不可写时拒绝替换且原数据不变`() = runBlocking {
        seedLedger(repoB)
        val before = repoB.loadAll()
        val backup = repoA.backupSnapshot("t", "1.0")
        val blocker = File(snapDir.parentFile, "blocker_" + System.nanoTime())
        blocker.writeText("x")
        repoB.snapshotDir = blocker      // 目标路径是文件，无法建目录
        try {
            repoB.applyRestore(BackupCodec.toEntities(backup), repoB.snapshotDir!!)
            fail("快照不可写应阻止恢复")
        } catch (e: PlanException) {
            assertTrue(e.message!!.contains("停止恢复"))
        } finally {
            repoB.snapshotDir = snapDir
            blocker.delete()
        }
        assertEquals(before, repoB.loadAll())
    }

    @Test
    fun `恢复4 - 坏 JSON 与不支持的 schema 被拒绝`() = runBlocking {
        val badTexts = listOf("", "null", "[]", "{\"schemaVersion\":1}", "垃圾内容")
        for (t in badTexts) {
            try {
                BackupCodec.decode(t)
                fail("应当解析失败：$t")
            } catch (e: IllegalArgumentException) { /* expected */ }
        }
        val wrongSchema = BackupCodec.decode(
            BackupCodec.encode(
                BackupV1(
                    schemaVersion = 99, exportedAt = "t", appVersion = "1.0",
                    counts = BackupCounts(0, 0, 0),
                    schedules = emptyList(), todos = emptyList(), countdowns = emptyList(),
                ),
            ),
        )
        val r = BackupValidator.validate(wrongSchema)
        assertTrue(r is ValidateResult.Bad)
        assertTrue((r as ValidateResult.Bad).errors.any { it.contains("schemaVersion") })
    }

    @Test
    fun `恢复5 - 超长字段与非法日期被拒（与写入同规则）`() = runBlocking {
        seedLedger(repoA)
        val base = BackupCodec.decode(
            BackupCodec.encode(repoA.backupSnapshot("t", "1.0")),
        )
        // 超长备注（>1000）
        val longNote = base.copy(
            todos = base.todos.map { it.copy(note = "超".repeat(1001)) },
        )
        val r1 = BackupValidator.validate(longNote)
        assertTrue(r1 is ValidateResult.Bad)

        // 非法日期 2026-02-31
        val badDate = base.copy(
            schedules = base.schedules.map { it.copy(startDate = "2026-02-31") },
        )
        val r2 = BackupValidator.validate(badDate)
        assertTrue(r2 is ValidateResult.Bad)

        // 结束早于开始
        val reversed = base.copy(
            schedules = base.schedules.map {
                it.copy(startDate = "2026-10-26", endDate = "2026-10-25")
            },
        )
        val r3 = BackupValidator.validate(reversed)
        assertTrue(r3 is ValidateResult.Bad)
    }

    @Test
    fun `恢复6 - 重复 ID 被拒`() = runBlocking {
        seedLedger(repoA)
        val base = BackupCodec.decode(
            BackupCodec.encode(repoA.backupSnapshot("t", "1.0")),
        )
        val dup = base.copy(todos = base.todos + base.todos.first())
        val r = BackupValidator.validate(dup)
        assertTrue(r is ValidateResult.Bad)
        assertTrue((r as ValidateResult.Bad).errors.any { it.contains("重复 ID") })
    }

    @Test
    fun `取消恢复 - 只解析不 apply 数据不变`() = runBlocking {
        seedLedger(repoB)
        seedLedger(repoA)
        val before = repoB.loadAll()
        val backup = repoA.backupSnapshot("t", "1.0")
        // 只验证预览
        when (val v = BackupValidator.validate(backup)) {
            is ValidateResult.Ok -> assertEquals(2, v.preview.schedules)
            else -> fail()
        }
        assertEquals(before, repoB.loadAll())
    }
}
