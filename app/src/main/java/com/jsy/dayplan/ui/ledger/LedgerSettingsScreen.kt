package com.jsy.dayplan.ui.ledger

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.ledger.vm.LedgerTab
import com.jsy.dayplan.ledger.vm.LedgerViewModel
import com.jsy.dayplan.ui.GlassCard
import com.jsy.dayplan.ui.pageHorizontalPadding
import java.time.LocalDate

/** 设置子页内容：账本备份/恢复（小账本兼容 JSON）、CSV、快照、管理入口。 */
@Composable
fun LedgerSettingsContent(
    vm: LedgerViewModel,
    onManageCategories: () -> Unit,
    onManageAccounts: () -> Unit,
) {
    val context = LocalContext.current
    val txns by vm.txns.collectAsState()
    val transfers by vm.transfers.collectAsState()
    // 导出/导入后手动刷新「最近导出时间」「快照数」——prefs 与文件不是响应式状态
    var refreshTick by remember { mutableIntStateOf(0) }
    val lastExport = remember(refreshTick) { vm.lastExportAt() }
    val snapshotCount = remember(refreshTick) { vm.snapshotFiles().size }
    val pagePad = pageHorizontalPadding()

    var restorePreviewShown by remember { mutableStateOf(false) }
    var restoreErrors by remember { mutableStateOf<List<String>?>(null) }
    val previewSummary by vm.restorePreviewSummary.collectAsState()

    val createJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            vm.launchExportJson(uri) { r ->
                refreshTick++
                Toast.makeText(
                    context,
                    if (r.ok) "已导出：${r.name}" else "导出失败：${r.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        if (uri != null) {
            vm.launchExportCsv(uri) { r ->
                Toast.makeText(
                    context,
                    if (r.ok) "已导出：${r.name}" else "导出失败：${r.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val createSnapshot = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            vm.launchExportSnapshot(uri) { r ->
                Toast.makeText(
                    context,
                    if (r.ok) "快照已导出：${r.name}" else "导出失败：${r.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            vm.launchParse(uri) { preview, errors, _ ->
                if (preview != null) restorePreviewShown = true
                else restoreErrors = errors
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pagePad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 当前账本状态卡
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
                Text(
                    "当前账本：${txns.size} 笔账目、${transfers.size} 笔转账",
                    Modifier.padding(top = 4.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "最近成功导出：$lastExport",
                    Modifier.padding(top = 2.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = { openBackup.launch(arrayOf("application/json")) },
            modifier = Modifier.fillMaxWidth().testTag("ledger_import"),
        ) {
            Text("导入小账本旧备份 / 恢复账本备份（JSON）")
        }
        OutlinedButton(
            onClick = { createJson.launch("小账本备份_${LocalDate.now()}.json") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("导出账本备份（JSON，兼容小账本 v1.3）")
        }
        OutlinedButton(
            onClick = { createCsv.launch("小账本明细_${LocalDate.now()}.csv") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("导出明细 CSV（仅供 Excel 查看）")
        }

        if (snapshotCount > 0) {
            Text(
                "本机保留 $snapshotCount 份恢复前自动快照（导入前的完整账本，可用于回退）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { createSnapshot.launch("小账本_恢复快照_${LocalDate.now()}.json") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("导出最近的恢复快照（JSON）")
            }
        }

        // 管理入口
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text("管理", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = onManageCategories, modifier = Modifier.weight(1f)) {
                        Text("管理分类")
                    }
                    TextButton(onClick = onManageAccounts, modifier = Modifier.weight(1f)) {
                        Text("管理账户")
                    }
                }
            }
        }

        // 使用说明
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("使用说明", style = MaterialTheme.typography.titleMedium)
                Text(
                    "· 旧版小账本（v1.x）导出的 JSON 备份可直接导入；导入是【完整替换】，不是合并。\n" +
                        "· 确认导入前会先预览数量与金额；导入前自动生成可回退快照，任何失败都不会改动现有账本。\n" +
                        "· 建议导入前先「导出账本备份」，给自己留一条退路。\n" +
                        "· 日序主备份（日程/待办/倒数日）不包含账本数据；账本在这里单独备份。\n" +
                        "· 备份为明文 JSON，请保存在您信任的位置；本应用不联网、不上传。\n" +
                        "· 「余额」均指记账余额（期初 + 收入 − 支出 ± 转账）。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "小账本 · 自 v1.8.0 并入日序 · 数据存于应用私有目录 ledger.db",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = com.jsy.dayplan.ui.ledger.LIST_BOTTOM_CLEARANCE),
        )
    }

    // 恢复确认对话框
    if (restorePreviewShown) {
        AlertDialog(
            onDismissRequest = { restorePreviewShown = false },
            title = { Text("确认导入并替换账本？") },
            text = { Text(previewSummary) },
            confirmButton = {
                TextButton(onClick = {
                    restorePreviewShown = false
                    vm.launchApplyRestore { ok, msg ->
                        refreshTick++
                        if (ok) {
                            vm.selectTab(LedgerTab.OVERVIEW)
                            Toast.makeText(context, "导入完成，已切换到明细", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "导入失败（原数据未变）：$msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text(
                        "替换账本全部数据",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { restorePreviewShown = false }) { Text("取消") } },
        )
    }
    restoreErrors?.let { errors ->
        AlertDialog(
            onDismissRequest = { restoreErrors = null },
            title = { Text("备份文件校验未通过") },
            text = { Text(errors.joinToString("\n") { "· $it" }) },
            confirmButton = { TextButton(onClick = { restoreErrors = null }) { Text("知道了") } },
        )
    }
}
