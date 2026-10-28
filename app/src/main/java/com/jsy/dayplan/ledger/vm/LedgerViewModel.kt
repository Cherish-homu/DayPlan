package com.jsy.dayplan.ledger.vm

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.backup.BackupIO
import com.jsy.dayplan.ledger.backup.BackupCodec
import com.jsy.dayplan.ledger.backup.BackupValidator
import com.jsy.dayplan.ledger.backup.RestorePreview
import com.jsy.dayplan.ledger.backup.ValidateResult
import com.jsy.dayplan.ledger.core.AccountBalance
import com.jsy.dayplan.ledger.core.Calculator
import com.jsy.dayplan.ledger.core.LedgerRules
import com.jsy.dayplan.ledger.core.Money
import com.jsy.dayplan.ledger.core.MonthStats
import com.jsy.dayplan.ledger.core.TxnRow
import com.jsy.dayplan.ledger.data.AccountEntity
import com.jsy.dayplan.ledger.data.BudgetEntity
import com.jsy.dayplan.ledger.data.CategoryEntity
import com.jsy.dayplan.ledger.data.TransactionEntity
import com.jsy.dayplan.ledger.data.TransferEntity
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 账本首页四个子页；状态保存在 VM，页面切换不丢筛选/月份。 */
enum class LedgerTab { OVERVIEW, STATS, ACCOUNTS, SETTINGS }

/**
 * 账本 ViewModel：导航不再自管理（由日序 Screen 栈负责），
 * 这里只持有数据流、月份/筛选与备份导入导出。
 */
class LedgerViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as DayPlanApp).ledgerRepo
    private val sprefs: android.content.SharedPreferences =
        app.getSharedPreferences("ledger_prefs", Context.MODE_PRIVATE)

    init {
        repo.snapshotDir = File(app.filesDir, "ledger_restore_snapshots")
        viewModelScope.launch { repo.seedDefaultsIfEmpty() }
    }

    // ---------- 子页切换 ----------
    private val _tab = MutableStateFlow(LedgerTab.OVERVIEW)
    val tab: StateFlow<LedgerTab> = _tab
    fun selectTab(t: LedgerTab) { _tab.value = t }

    // ---------- 数据流 ----------
    val accounts = repo.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories = repo.categories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val txns = repo.txns.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val transfers = repo.transfers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val budgets = repo.budgets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- 月份与筛选 ----------
    private val _selectedMonth = MutableStateFlow(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")))
    val selectedMonth: StateFlow<String> = _selectedMonth
    fun prevMonth() { _selectedMonth.value = Calculator.prevMonth(_selectedMonth.value) }
    fun nextMonth() { _selectedMonth.value = Calculator.nextMonth(_selectedMonth.value) }

    val filterCategoryId = MutableStateFlow<Long?>(null)
    val filterAccountId = MutableStateFlow<Long?>(null)
    val filterKeyword = MutableStateFlow("")

    /** 当月（含首尾日）的账目 + 分类/账户/关键词筛选，与统计口径一致。 */
    val visibleTxns: StateFlow<List<TransactionEntity>> = combine(
        txns, _selectedMonth, filterCategoryId, filterAccountId, filterKeyword,
    ) { t, month, cat, acc, kw ->
        com.jsy.dayplan.ledger.core.LedgerFilter.filterTxns(t, month, cat, acc, kw)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当月转账（修掉小账本 v1.3 把全部月份转账混进当月的问题）。 */
    val visibleTransfers: StateFlow<List<TransferEntity>> = combine(
        transfers, _selectedMonth, filterAccountId,
    ) { tr, month, acc ->
        com.jsy.dayplan.ledger.core.LedgerFilter.filterTransfers(tr, month, acc)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val calcError = MutableStateFlow<String?>(null)

    val monthStats: StateFlow<MonthStats> = combine(txns, _selectedMonth) { t, month ->
        try {
            Calculator.computeMonthStats(
                month,
                t.map { TxnRow(it.id, it.type, it.amountFen, it.categoryId, it.accountId, it.bizDate, it.note) },
            )
        } catch (e: Exception) {
            calcError.value = e.message ?: "计算溢出"
            MonthStats(month, 0, 0, 0, emptyList())
        }
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        MonthStats(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")), 0, 0, 0, emptyList()),
    )

    val balances: StateFlow<List<AccountBalance>> = combine(
        accounts, txns, transfers,
    ) { a, t, tr ->
        try {
            Calculator.computeBalances(
                a.map { com.jsy.dayplan.ledger.core.AccountRow(it.id, it.name, it.openingFen, it.archived) },
                t.map { TxnRow(it.id, it.type, it.amountFen, it.categoryId, it.accountId, it.bizDate, it.note) },
                tr.map { com.jsy.dayplan.ledger.core.TransferRow(it.id, it.amountFen, it.fromAccountId, it.toAccountId, it.bizDate, it.note) },
            )
        } catch (e: Exception) {
            calcError.value = e.message ?: "计算溢出"
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val monthBudget: StateFlow<BudgetEntity?> = combine(budgets, _selectedMonth) { b, m ->
        b.firstOrNull { it.month == m }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---------- 保存（防连点） ----------
    private var savingJob: Job? = null
    val saving = MutableStateFlow(false)
    val saveError = MutableStateFlow<String?>(null)

    fun saveTxn(t: TransactionEntity, onDone: () -> Unit) {
        if (savingJob?.isActive == true) return   // 防连点
        saveError.value = null
        saving.value = true
        savingJob = viewModelScope.launch {
            try {
                repo.saveTxn(t)
                sprefs.edit().putLong("lastAccountId", t.accountId).apply()
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                saveError.value = e.message ?: "保存失败"
            } finally {
                saving.value = false
            }
        }
    }

    fun saveTransfer(t: TransferEntity, onDone: () -> Unit) {
        if (savingJob?.isActive == true) return
        saveError.value = null
        saving.value = true
        savingJob = viewModelScope.launch {
            try {
                repo.saveTransfer(t)
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                saveError.value = e.message ?: "保存失败"
            } finally {
                saving.value = false
            }
        }
    }

    fun deleteTxn(id: Long) = viewModelScope.launch { repo.deleteTxn(id) }
    fun deleteTransfer(id: Long) = viewModelScope.launch { repo.deleteTransfer(id) }

    // ---------- 备份导出 ----------
    suspend fun exportBackupJson(): String {
        // 从单一一致性数据库事务快照生成（与恢复前置快照同一读取口径）
        val backup = repo.backupSnapshot(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        )
        return BackupCodec.encode(backup)
    }

    suspend fun transactionsCsv(): String {
        val catName = repo.dao.allCategories().associate { it.id to it.name }
        val accName = repo.dao.allAccounts().associate { it.id to it.name }
        val header = listOf("业务日期", "时间", "类型", "分类", "账户", "金额(元)", "备注")
        val rows = repo.dao.allTxns().map { t ->
            listOf(
                t.bizDate, t.bizTime,
                if (t.type == 1) "收入" else "支出",
                catName[t.categoryId] ?: "未知分类",
                accName[t.accountId] ?: "未知账户",
                Money.formatFen(t.amountFen),
                t.note,
            )
        }
        val tRows = repo.dao.allTransfers().map { t ->
            listOf(
                t.bizDate, t.bizTime, "转账",
                "转入 ${accName[t.toAccountId] ?: "?"} / 转出 ${accName[t.fromAccountId] ?: "?"}",
                accName[t.fromAccountId] ?: "未知账户",
                Money.formatFen(t.amountFen),
                t.note,
            )
        }
        val all = (rows + tRows).sortedByDescending { it[0] + it[1] }
        return com.jsy.dayplan.ledger.backup.CsvExporter.transactionsCsv(header, all)
    }

    /** 解析 + 校验备份文本，返回 (预览, 错误列表)。 */
    suspend fun parseBackup(text: String): Pair<RestorePreview?, List<String>> =
        withContext(Dispatchers.Default) {
            try {
                val b = BackupCodec.decode(text)
                when (val v = BackupValidator.validate(b)) {
                    is ValidateResult.Ok -> v.preview to emptyList()
                    is ValidateResult.Bad -> null to v.errors
                }
            } catch (e: Exception) {
                null to listOf(e.message ?: "解析失败")
            }
        }

    /** 用户确认后执行整库替换。 */
    suspend fun applyRestore(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val b = BackupCodec.decode(text)
            when (val v = BackupValidator.validate(b)) {
                is ValidateResult.Bad ->
                    Result.failure(IllegalStateException(v.errors.joinToString("\n")))
                is ValidateResult.Ok -> {
                    repo.applyRestore(BackupCodec.toEntities(v.backup))
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 导入数据里最新的业务月份（账目与转账都考虑）；没有则返回 null。 */
    suspend fun latestMonthOf(text: String): String? = withContext(Dispatchers.Default) {
        try {
            val b = BackupCodec.decode(text)
            val dates = b.transactions.map { it.bizDate } + b.transfers.map { it.bizDate }
            dates.filter { it.length >= 7 }.maxOrNull()?.take(7)
        } catch (e: Exception) {
            null
        }
    }

    fun markExportedNow() {
        sprefs.edit().putString(
            "lastExportAt",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        ).apply()
    }

    fun lastExportAt(): String = sprefs.getString("lastExportAt", "从未导出") ?: "从未导出"

    fun defaultAccountId(): Long? =
        pickDefaultAccountId(sprefs.getLong("lastAccountId", -1L), accounts.value)

    // ---------- SAF 备份/恢复 ----------
    data class FileOpResult(val name: String, val ok: Boolean, val message: String)

    private fun fileNameOf(uri: Uri): String {
        val c = getApplication<Application>().contentResolver
        c.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cur ->
                if (cur.moveToFirst()) {
                    val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cur.getString(idx) ?: "未命名文件"
                }
            }
        return "未命名文件"
    }

    fun launchExportJson(uri: Uri, cb: (FileOpResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = fileNameOf(uri)
            try {
                val text = exportBackupJson()
                val cr = getApplication<Application>().contentResolver
                cr.openOutputStream(uri, "wt")?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                    os.flush()
                } ?: throw IllegalStateException("无法打开所选位置")
                // 回读并解析比较：证明写出内容与预期备份完整一致（不只是长度）
                val readBack = cr.openInputStream(uri)?.use {
                    BackupIO.readTextBounded(it, LedgerRules.MAX_FILE_BYTES)
                } ?: throw IllegalStateException("回读失败")
                if (readBack != text) throw IllegalStateException("写回校验不一致，文件可能不完整")
                when (val v = BackupValidator.validate(BackupCodec.decode(readBack))) {
                    is ValidateResult.Bad ->
                        throw IllegalStateException("回读校验失败：" + v.errors.first())
                    is ValidateResult.Ok -> Unit
                }
                markExportedNow()
                withContext(Dispatchers.Main) { cb(FileOpResult(name, true, "")) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { cb(FileOpResult(name, false, e.message ?: "未知错误")) }
            }
        }
    }

    fun launchExportCsv(uri: Uri, cb: (FileOpResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = fileNameOf(uri)
            try {
                val text = transactionsCsv()
                val cr = getApplication<Application>().contentResolver
                cr.openOutputStream(uri, "wt")?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                    os.flush()
                } ?: throw IllegalStateException("无法打开所选位置")
                withContext(Dispatchers.Main) { cb(FileOpResult(name, true, "")) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { cb(FileOpResult(name, false, e.message ?: "未知错误")) }
            }
        }
    }

    val restorePreviewSummary = MutableStateFlow("")
    private var pendingRestoreText: String? = null

    fun launchParse(uri: Uri, cb: (RestorePreview?, List<String>, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val text = cr.openInputStream(uri)?.use {
                    BackupIO.readTextBounded(it, LedgerRules.MAX_FILE_BYTES)
                } ?: throw IllegalStateException("无法读取所选文件")
                val (preview, errors) = parseBackup(text)
                withContext(Dispatchers.Main) {
                    if (preview != null) {
                        pendingRestoreText = text
                        restorePreviewSummary.value = buildString {
                            append("备份内容：\n")
                            append("· 账户 ${preview.accounts} 个、分类 ${preview.categories} 个\n")
                            append("· 账目 ${preview.transactions} 笔、转账 ${preview.transfers} 笔、月预算 ${preview.budgets} 项\n")
                            append("· 收入合计 ${Money.formatYuan(preview.incomeFen)}、支出合计 ${Money.formatYuan(preview.expenseFen)}\n\n")
                            append("确认后将【替换】本机账本全部数据（先自动生成可回退快照；导入中任何失败都不会改动现有账本）。")
                        }
                        cb(preview, emptyList(), text)
                    } else {
                        // 解析/校验失败时，若文件形状像日序主备份（有 schedules、没有 budgets），
                        // 给出更明确的提示；正常账本备份即使备注含 "schedules" 也会解码成功，不会走到这里。
                        val dayplanLike = text.contains("\"schedules\"") && !text.contains("\"budgets\"")
                        val msgs = if (dayplanLike && errors.isNotEmpty()) {
                            listOf("这是日序主备份（日程/待办/倒数日），不能导入账本；请选择小账本导出的完整备份 JSON。")
                        } else errors
                        cb(null, msgs, null)
                    }
                }
            } catch (e: com.jsy.dayplan.backup.BackupTooLargeException) {
                withContext(Dispatchers.Main) { cb(null, listOf(e.message ?: "文件过大"), null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { cb(null, listOf(e.message ?: "读取失败"), null) }
            }
        }
    }

    fun launchApplyRestore(cb: (Boolean, String) -> Unit) {
        val text = pendingRestoreText
        if (text == null) {
            cb(false, "没有待恢复的备份内容")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val r = applyRestore(text)
            if (r.isSuccess) {
                pendingRestoreText = null
                // 导入成功：月份切到导入数据最新的业务月份，用户马上能看到旧账
                latestMonthOf(text)?.let { _selectedMonth.value = it }
            }
            withContext(Dispatchers.Main) {
                r.fold(
                    onSuccess = { cb(true, "") },
                    onFailure = { cb(false, it.message ?: "未知错误") },
                )
            }
        }
    }

    // ---------- 恢复快照（回退入口） ----------
    fun snapshotFiles(): List<File> = repo.snapshots()

    fun latestSnapshotText(): String? = repo.snapshots().firstOrNull()?.readText(Charsets.UTF_8)

    // ---------- 预算 ----------
    fun setBudget(month: String, fen: Long) {
        viewModelScope.launch { repo.setBudget(month, fen) }
    }

    /** 同步保存预算；返回错误信息（null=成功），供对话框内直接显示。 */
    fun setBudgetBlocking(month: String, fen: Long): String? =
        kotlinx.coroutines.runBlocking {
            try {
                repo.setBudget(month, fen)
                null
            } catch (e: Exception) {
                e.message ?: "保存失败"
            }
        }

    // ---------- 管理操作（单条小操作） ----------
    fun addCategoryBlocking(name: String, type: Int) =
        kotlinx.coroutines.runBlocking { repo.addCategory(name, type) }

    fun renameCategoryBlocking(id: Long, name: String) =
        kotlinx.coroutines.runBlocking { repo.renameCategory(id, name) }

    fun archiveCategoryBlocking(id: Long) =
        kotlinx.coroutines.runBlocking { repo.archiveCategory(id) }

    fun addAccountBlocking(name: String, openingFen: Long) =
        kotlinx.coroutines.runBlocking { repo.addAccount(name, openingFen) }

    fun renameAccountBlocking(id: Long, name: String) =
        kotlinx.coroutines.runBlocking { repo.renameAccount(id, name) }

    fun setOpeningBlocking(id: Long, fen: Long) =
        kotlinx.coroutines.runBlocking { repo.setOpening(id, fen) }

    fun archiveAccountBlocking(id: Long) =
        kotlinx.coroutines.runBlocking { repo.archiveAccount(id) }

    fun launchExportSnapshot(uri: Uri, cb: (FileOpResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = fileNameOf(uri)
            try {
                val text = latestSnapshotText()
                    ?: throw IllegalStateException("当前没有可导出的恢复快照")
                val cr = getApplication<Application>().contentResolver
                cr.openOutputStream(uri, "wt")?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                    os.flush()
                } ?: throw IllegalStateException("无法打开所选位置")
                withContext(Dispatchers.Main) { cb(FileOpResult(name, true, "")) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { cb(FileOpResult(name, false, e.message ?: "未知错误")) }
            }
        }
    }
}

/** 纯函数便于测试：只默认到未归档账户，避免新账目静默存进已归档账户。 */
fun pickDefaultAccountId(lastId: Long, accounts: List<AccountEntity>): Long? {
    if (lastId > 0 && accounts.any { !it.archived && it.id == lastId }) return lastId
    return accounts.firstOrNull { !it.archived }?.id
}
