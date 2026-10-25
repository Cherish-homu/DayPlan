package com.jsy.dayplan.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.R
import com.jsy.dayplan.ai.AiAction
import com.jsy.dayplan.ai.AiException
import com.jsy.dayplan.ai.ActionReview
import com.jsy.dayplan.ai.ApplyOutcome
import com.jsy.dayplan.ai.BatchRecord
import com.jsy.dayplan.ai.ChatEntry
import com.jsy.dayplan.ai.UndoOutcome
import com.jsy.dayplan.ai.attach.AttachKind
import com.jsy.dayplan.ai.attach.AttachLimits
import com.jsy.dayplan.ai.attach.PendingAttachment
import com.jsy.dayplan.vm.AiViewModel
import com.jsy.dayplan.vm.AttachMeta
import com.jsy.dayplan.vm.SendState
import com.jsy.dayplan.vm.UiBatch
import java.io.File

/** 聊天页：消息 + 计划卡片 + 附件输入。沿用蓝白/深色主题。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    vm: AiViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEntity: (kind: String, id: Long) -> Unit = { _, _ -> },
    reduceMotion: Boolean = false,
) {
    val context = LocalContext.current
    val entries by vm.entries.collectAsState()
    val batches by vm.batches.collectAsState()
    val busyIds by vm.busyBatchIds.collectAsState()
    val contextAtts by vm.contextAttachments.collectAsState()
    val input by vm.input.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val attachmentMeta by vm.attachmentMeta.collectAsState()
    val pdfPages by vm.pdfPages.collectAsState()
    val xlsxSheets by vm.xlsxSheets.collectAsState()
    val sendState by vm.sendState.collectAsState()
    val configVersion by vm.configVersion.collectAsState()
    val savedNotice by vm.lastSavedNotice.collectAsState()
    val listState = rememberLazyListState()

    var showClearConfirm by remember { mutableStateOf(false) }
    var editAction by remember { mutableStateOf<Pair<String, AiAction>?>(null) }
    var attachDialog by remember { mutableStateOf<PendingAttachment?>(null) }

    val readiness = remember(configVersion) { vm.readiness() }

    // 附件选择：Photo Picker（图片）+ SAF（文件），点发送才真正联网
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(6),
    ) { uris -> uris.forEach { vm.addAttachment(it, "image/*") } }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> vm.addAttachment(uri, null) }

    LaunchedEffect(Unit) {
        // 进入聊天页时从 DB 边界/批次事实重载：恢复/快照回退后旧草稿立即显示失效
        vm.reload()
    }
    LaunchedEffect(entries.size, batches.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val chanStatus = ChanStatusLogic.from(
                        sendState,
                        pendingCount = batches.sumOf { it.record.actions.size },
                        lastSaved = savedNotice,
                    )
                    val statusLine = when {
                        chanStatus.mood != ChanMood.IDLE -> statusText(chanStatus)
                        vm.isMockActive() -> "演示(Mock)模式 · 未连接真实服务"
                        readiness == com.jsy.dayplan.ai.ChatController.Readiness.NO_KEY ->
                            "未配置 API Key"

                        readiness == com.jsy.dayplan.ai.ChatController.Readiness.DISABLED ->
                            "助手未启用"

                        else -> "模型：${vm.config().model}"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChanAvatar(
                            size = 32.dp,
                            animate = !reduceMotion,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("AI 助手")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    statusLine,
                                    fontSize = 11.sp,
                                    color = if (chanStatus.mood == ChanMood.ERROR)
                                        MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                if (chanStatus.mood == ChanMood.THINKING) {
                                    Spacer(Modifier.width(4.dp))
                                    ThinkingDots(animate = !reduceMotion)
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清空对话及附件缓存")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "AI 设置")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 未配置提示：跳设置，而不是假聊天
            if (readiness != com.jsy.dayplan.ai.ChatController.Readiness.READY) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (readiness == com.jsy.dayplan.ai.ChatController.Readiness.NO_KEY) {
                                    "还没有配置 DeepSeek API Key"
                                } else {
                                    "AI 助手未启用"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "离线功能不受影响；配置后即可在聊天中整理日程。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onOpenSettings) { Text("去设置") }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (entries.isEmpty()) {
                    item { WelcomeCard(vm, onExample = { vm.input.value = it }) }
                }
                items(entries, key = { "${it.atEpochMs}-${it.text.hashCode()}-${it.role}" }) { e ->
                    when (e.kind) {
                        "plan" -> PendingBatchCard(
                            vm = vm,
                            batchId = e.batchId ?: return@items,
                            onEdit = { editAction = it },
                            onOpenEntity = onOpenEntity,
                            busy = busyIds.contains(e.batchId),
                        )

                        "result" -> AppliedBatchCard(
                            vm = vm,
                            batchId = e.batchId ?: return@items,
                            onOpenEntity = onOpenEntity,
                            busy = busyIds.contains(e.batchId),
                        )

                        else -> MessageBubble(e)
                    }
                }
                // 进程回收后仍待确认、但不在历史里的批次（兜底展示）
                items(
                    batches.filter { b -> entries.none { it.batchId == b.record.batchId } },
                    key = { it.record.batchId },
                ) { b ->
                    PendingBatchCard(
                        vm, b.record.batchId, onEdit = { editAction = it },
                        onOpenEntity = onOpenEntity, busy = busyIds.contains(b.record.batchId),
                    )
                }
                item {
                    // 最近一条消息的用量/警告展示
                    val last = entries.lastOrNull()
                    if (last != null && last.usageLine.isNotBlank()) {
                        Text(
                            last.usageLine,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }

            // 发送状态
            when (val s = sendState) {
                is SendState.Sending -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    if (s.stage.isNotBlank()) {
                        Text(
                            s.stage,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                        )
                    }
                }

                is SendState.Failed -> Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.message,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        if (s.retryable) {
                            TextButton(onClick = { vm.send() }) { Text("重试") }
                        }
                        TextButton(onClick = {
                            vm.sendState.value = SendState.Idle
                        }) { Text("知道了") }
                    }
                }

                else -> {}
            }

            InputBar(
                vm = vm,
                input = input,
                attachments = attachments,
                contextAttachments = contextAtts.map { it.fileName to it.id },
                meta = attachmentMeta,
                pdfPages = pdfPages,
                xlsxSheets = xlsxSheets,
                sending = sendState is SendState.Sending,
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onPickFile = {
                    pickFile.launch(
                        arrayOf(
                            "application/pdf",
                            "text/plain", "text/markdown", "text/csv",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        ),
                    )
                },
                onOpenAttachment = { attachDialog = it },
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空对话及附件缓存？") },
            text = {
                Text("将删除聊天记录、待确认计划与本应用缓存的所有附件副本；已保存的日程不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    vm.clearConversation()
                    Toast.makeText(context, "已清空对话与附件缓存", Toast.LENGTH_SHORT).show()
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
        )
    }

    editAction?.let { (batchId, action) ->
        ActionEditDialog(
            action = action,
            onSave = {
                vm.updateAction(batchId, it)
                editAction = null
            },
            onDismiss = { editAction = null },
        )
    }

    attachDialog?.let { att ->
        AttachmentOptionsDialog(
            att = att,
            meta = attachmentMeta[att.id],
            selectedPages = pdfPages[att.id],
            selectedSheet = xlsxSheets[att.id],
            onSetPages = { vm.setPdfPages(att.id, it) },
            onSetSheet = { vm.setXlsxSheet(att.id, it) },
            onDismiss = { attachDialog = null },
        )
    }
}

@Composable
private fun WelcomeCard(vm: AiViewModel, onExample: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChanAvatar(size = 36.dp, animate = false, contentDescription = null)
                Column {
                    Text("你好，我是日序 AI 助手", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "聊天内容将发送到 DeepSeek 官方 API 处理",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("试试这样说：", fontSize = 12.sp)
            com.jsy.dayplan.ai.AiPrompts.exampleUserPrompts().forEach { ex ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { onExample(ex) },
                ) {
                    Text(ex, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Text(
                "也可以点输入框左侧 “+” 添加课程表截图或 PDF/表格，再告诉我要导入哪些安排。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(e: ChatEntry) {
    val isUser = e.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            ChanAvatar(size = 32.dp, animate = false, contentDescription = null)
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 12.dp else 3.dp,
                topEnd = if (isUser) 3.dp else 12.dp,
                bottomStart = 12.dp, bottomEnd = 12.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    e.text,
                    fontSize = 14.sp,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (e.attachmentNames.isNotEmpty()) {
                    Text(
                        "附件：${e.attachmentNames.joinToString("、")}",
                        fontSize = 10.sp,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ---------- 计划卡片 ----------

private val OP_LABELS = mapOf(
    "create_schedule" to "新增日程",
    "create_todo" to "新增待办",
    "create_countdown" to "新增倒数日",
    "update_schedule" to "修改日程",
    "update_todo" to "修改待办",
    "update_countdown" to "修改倒数日",
)

fun actionDateTimeLine(a: AiAction): String = buildString {
    when {
        a.op.endsWith("schedule") -> {
            append(a.startDate ?: "?")
            if (a.endDate != null && a.endDate != a.startDate) append(" 至 ").append(a.endDate)
            if (a.allDay) append(" 全天") else append(" ").append(a.startTime ?: "?").append("-").append(a.endTime ?: "?")
            if (a.location.isNotBlank()) append(" · ").append(a.location)
        }

        a.op.endsWith("todo") -> {
            append(if (a.dueDate != null) "截止 ${a.dueDate}" else "无截止日期")
            if (a.dueTime != null) append(" ").append(a.dueTime)
            if (a.priority == 1) append(" · 重要")
        }

        else -> {
            append("目标 ").append(a.targetDate ?: "?")
            if (a.yearlyRepeat == true) append(" · 每年")
        }
    }
}

@Composable
private fun PendingBatchCard(
    vm: AiViewModel,
    batchId: String,
    onEdit: (Pair<String, AiAction>) -> Unit,
    onOpenEntity: (kind: String, id: Long) -> Unit = { _, _ -> },
    busy: Boolean = false,
) {
    val batches by vm.batches.collectAsState()
    val ui = batches.find { it.record.batchId == batchId }
    if (ui == null) {
        // 兼容旧历史：批次已保存/撤销时显示结果卡；数据恢复/清空后旧草稿明确失效
        val rec = vm.appliedBatch(batchId)
        if (rec?.applied != null) {
            AppliedBatchCard(vm, batchId, onOpenEntity, busy)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "此计划已失效（数据已恢复或对话已清空），不能继续导入；请重新发送需求。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        return
    }
    val rec = ui.record
    val context = LocalContext.current
    val page = ui.reviews.take(com.jsy.dayplan.ai.AiProtocol.PAGE_SIZE)
    val shown = if (ui.onlyIssues) {
        page.filter { r -> r.status != "ok" || r.conflicts.isNotEmpty() || r.duplicateOfExisting || r.duplicateInBatch.isNotEmpty() }
    } else page
    val selectedCount = ui.selection.count { it.value }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        if (rec.origin == "attachment") "附件批次 · 先预览" else "计划草稿",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "共 ${rec.actions.size} 项",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text("待确认", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
            }
            if (rec.reply.isNotBlank()) Text(rec.reply, fontSize = 13.sp)
            if (rec.attachmentNames.isNotEmpty()) {
                Text(
                    "附件：${rec.attachmentNames.joinToString("、")}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rec.attachmentIds.isNotEmpty()) {
                    Text(
                        "附件缓存仅保留有限时间；若预览缺失请重新选择文件核对，不会用旧内容顶替。",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            rec.questions.forEach { q ->
                Text("❓ $q", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            rec.warnings.forEach { w ->
                Text(
                    "⚠ $w",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row {
                FilterChip(
                    selected = ui.onlyIssues,
                    onClick = { vm.setOnlyIssues(batchId, !ui.onlyIssues) },
                    label = { Text("仅看疑点/冲突", fontSize = 11.sp) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = selectedCount == page.size && page.isNotEmpty(),
                    onClick = {
                        vm.selectAll(batchId, selectedCount != page.size)
                    },
                    label = { Text("全选/取消", fontSize = 11.sp) },
                )
            }

            shown.forEach { r -> ActionRow(r, ui.selection[r.action.clientActionId] ?: false, {
                vm.toggleSelect(batchId, r.action.clientActionId)
            }, { onEdit(batchId to r.action) }) }

            if (rec.actions.size > page.size) {
                Text(
                    "其余 ${rec.actions.size - page.size} 项请先保存本批后继续（每批最多 ${com.jsy.dayplan.ai.AiProtocol.PAGE_SIZE} 项，不会伪报全部完成）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 $selectedCount 项",
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        vm.applyBatch(batchId) { outcome ->
                            when (outcome) {
                                is ApplyOutcome.Success ->
                                    Toast.makeText(
                                        context,
                                        if (outcome.remindersPending) "已保存，提醒待重建：${outcome.reminderError}"
                                        else "已保存 ${outcome.batch.applied?.items?.size ?: 0} 项",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                is ApplyOutcome.Failed ->
                                    Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                is ApplyOutcome.PreviewRequired ->
                                    Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                is ApplyOutcome.Stale ->
                                    Toast.makeText(context, "数据已恢复/替换，本批已失效", Toast.LENGTH_LONG).show()
                                is ApplyOutcome.AlreadyApplied ->
                                    Toast.makeText(context, "本批已保存过，未重复写入", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = selectedCount > 0 && !busy,
                ) { Text(if (busy) "导入中…" else "导入选中 $selectedCount 项") }
            }
        }
    }
}

@Composable
private fun ActionRow(
    r: ActionReview,
    checked: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val a = r.action
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (r.status != "ok") MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (r.status == "ok") onToggle() },
                enabled = r.status == "ok",
                modifier = Modifier.size(32.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "${OP_LABELS[a.op] ?: a.op} · ${a.title}",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                Text(actionDateTimeLine(a), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.beforeLine.isNotBlank()) {
                    Text(
                        "变更前：${r.beforeLine}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val remind = when {
                    a.op.endsWith("schedule") -> when (a.remindMinutesBefore ?: 0) {
                        0 -> "提醒关闭"
                        else -> "提前 ${a.remindMinutesBefore} 分钟提醒"
                    }

                    a.op.endsWith("countdown") && a.remindEnabled == true ->
                        "提醒开启（${if (a.remindDaysBefore == 0) "当天" else "提前${a.remindDaysBefore}天"} ${a.remindTime ?: "09:00"}）"

                    else -> ""
                }
                if (remind.isNotBlank()) {
                    Text(remind, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                a.assumptions.forEach {
                    Text("假设：$it", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
                if (r.sourceLabel.isNotBlank()) {
                    Text(r.sourceLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r.problems.forEach {
                    Text("✗ $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                if (r.conflicts.isNotEmpty()) {
                    Text(
                        "⚠ 与现有日程时间重叠：${r.conflicts.joinToString("、")}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (r.duplicateOfExisting) {
                    Text("⚠ 与现有条目完全相同，默认不选中（可手动确认导入）", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
                if (r.duplicateInBatch.isNotEmpty()) {
                    Text("⚠ 与本批其他条目重复", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            TextButton(onClick = onEdit) { Text("编辑", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun AppliedBatchCard(
    vm: AiViewModel,
    batchId: String,
    onOpenEntity: (kind: String, id: Long) -> Unit,
    busy: Boolean = false,
) {
    val context = LocalContext.current
    // 优先 Room 提交事实；JSON 写失败/丢失时结果卡与撤销仍可用
    val appliedMap by vm.appliedRecords.collectAsState()
    val rec: BatchRecord? = appliedMap[batchId] ?: vm.appliedBatch(batchId)
    val applied = rec?.applied ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "✓ 已保存 ${applied.items.size} 项" +
                    if (applied.remindersPending) "（提醒待重建：${applied.reminderError}）" else "",
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (applied.remindersPending) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
            )
            applied.items.forEach { it ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "· ${OP_LABELS.entries.find { e -> e.key.startsWith("create") && it.kind == e.key.removePrefix("create_") }?.value ?: "已保存"}" +
                            "「${it.title}」（真实ID #${it.id}，版本 ${it.versionAfter}）",
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onOpenEntity(it.kind, it.id) }) {
                        Text("编辑/删除", fontSize = 11.sp)
                    }
                }
            }
            val skipped = rec.actions.size - applied.items.size
            if (skipped > 0) {
                Text(
                    "未导入 $skipped 项（未勾选，已放弃；如需导入请重新发送）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rec.state == "APPLIED") {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        vm.undoBatch(batchId) { outcome ->
                            when (outcome) {
                                is UndoOutcome.Success ->
                                    Toast.makeText(context, "已撤销本批", Toast.LENGTH_SHORT).show()
                                is UndoOutcome.Partial -> Toast.makeText(
                                    context,
                                    "已撤销；以下条目因你后来又编辑过而保留：${outcome.keptTitles.joinToString("、")}",
                                    Toast.LENGTH_LONG,
                                ).show()
                                is UndoOutcome.Failed ->
                                    Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                is UndoOutcome.Stale ->
                                    Toast.makeText(context, "数据已恢复/替换，本批撤销已失效", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Text(if (busy) "撤销中…" else "撤销本批") }
            } else if (rec.state == "UNDONE") {
                Text("本批已撤销", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- 输入区 ----------

@Composable
private fun InputBar(
    vm: AiViewModel,
    input: String,
    attachments: List<PendingAttachment>,
    contextAttachments: List<Pair<String, String>>,
    meta: Map<String, AttachMeta>,
    pdfPages: Map<String, Set<Int>>,
    xlsxSheets: Map<String, String>,
    sending: Boolean,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onOpenAttachment: (PendingAttachment) -> Unit,
) {
    var showAttachMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (attachments.isEmpty() && contextAttachments.isNotEmpty()) {
                // 澄清追问时仍引用上一轮材料；可逐项移除
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    contextAttachments.take(AttachLimits.MAX_COUNT).forEach { (name, id) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                Modifier.padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "继续参考：${name.take(16)}",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                IconButton(
                                    onClick = { vm.removeContextAttachment(id) },
                                    modifier = Modifier.size(24.dp),
                                ) { Icon(Icons.Filled.Close, "移除参考材料", Modifier.size(14.dp)) }
                            }
                        }
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.take(AttachLimits.MAX_COUNT).forEach { att ->
                        val m = meta[att.id]
                        val rangeLabel = when (att.kind) {
                            AttachKind.PDF -> pdfPages[att.id]?.let { p ->
                                if (p.isEmpty()) "全部页" else "第 ${p.minOrNull()}–${p.maxOrNull()} 页（${p.size}）"
                            } ?: (m?.pdfPageCount?.let { "全部 $it 页" } ?: "默认页")

                            AttachKind.XLSX -> xlsxSheets[att.id] ?: "第一个表"
                            else -> ""
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clickable { onOpenAttachment(att) },
                        ) {
                            Row(
                                Modifier.padding(start = 4.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AttachmentThumb(att)
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    buildString {
                                        append(att.fileName.take(14))
                                        append('\n').append(att.kind.display).append(' ').append(att.sizeLabel)
                                        if (rangeLabel.isNotBlank()) append('\n').append(rangeLabel)
                                    },
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(
                                    onClick = { vm.removeAttachment(att.id) },
                                    modifier = Modifier.size(24.dp),
                                ) { Icon(Icons.Filled.Close, "移除", Modifier.size(14.dp)) }
                            }
                        }
                    }
                }
                // 只发附件时给出可选意图，不直接写库
                if (input.isBlank()) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "提取所有日程" to "请从附件中提取所有可执行的日程、待办和倒数日；不确定的信息先问我。",
                            "只提取截止日期" to "只提取附件中的截止日期/考试日/交作业日，整理为待办或倒数日；不要编造时间。",
                        ).forEach { (label, prompt) ->
                            FilterChip(
                                selected = false,
                                onClick = { vm.input.value = prompt },
                                label = { Text(label, fontSize = 10.sp) },
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { showAttachMenu = !showAttachMenu }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加附件")
                    }
                    androidx.compose.material3.DropdownMenu(
                        showAttachMenu,
                        onDismissRequest = { showAttachMenu = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("相册图片（JPG/PNG/WebP）") },
                            onClick = { showAttachMenu = false; onPickImage() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("文件（PDF/TXT/CSV/DOCX/XLSX）") },
                            onClick = { showAttachMenu = false; onPickFile() },
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 4000) vm.input.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (attachments.isEmpty()) "说明要导入的安排…" else "可补充要求，如“只导入我要参加的三场”") },
                    maxLines = 4,
                )
                if (!sending) {
                    IconButton(
                        onClick = { vm.send() },
                        enabled = input.isNotBlank() || attachments.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    IconButton(onClick = { vm.cancelSend() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "取消请求",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                "附件在点击发送后才会通过网络发送到 DeepSeek；仅发送你选择的范围。点附件可预览/选择页或工作表。",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** 小缩略图：图片按采样解码，文档用类型标签；点击打开预览/范围选择。 */
@Composable
private fun AttachmentThumb(att: PendingAttachment) {
    val isImage = att.kind == AttachKind.IMAGE_JPEG ||
        att.kind == AttachKind.IMAGE_PNG || att.kind == AttachKind.IMAGE_WEBP
    if (isImage) {
        val bmp = remember(att.file.absolutePath, att.file.lastModified()) {
            try {
                BitmapFactory.decodeFile(
                    att.file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = 8 },
                )
            } catch (e: Exception) {
                null
            }
        }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = att.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(5.dp)),
            )
        } else {
            Text("图片", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    } else {
        Surface(
            shape = RoundedCornerShape(5.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(38.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    when (att.kind) {
                        AttachKind.PDF -> "PDF"
                        AttachKind.DOCX -> "DOCX"
                        AttachKind.XLSX -> "XLSX"
                        AttachKind.CSV -> "CSV"
                        AttachKind.MD -> "MD"
                        else -> "TXT"
                    },
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 附件预览与范围选择：图片预览；PDF 选页（≤20 页）；XLSX 选工作表。 */
@Composable
private fun AttachmentOptionsDialog(
    att: PendingAttachment,
    meta: AttachMeta?,
    selectedPages: Set<Int>?,
    selectedSheet: String?,
    onSetPages: (Set<Int>) -> Unit,
    onSetSheet: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    when (att.kind) {
        AttachKind.IMAGE_JPEG, AttachKind.IMAGE_PNG, AttachKind.IMAGE_WEBP -> {
            val bmp = remember(att.file.absolutePath) {
                try {
                    BitmapFactory.decodeFile(
                        att.file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = 4 },
                    )
                } catch (e: Exception) {
                    null
                }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(att.fileName) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = att.fileName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(240.dp),
                            )
                        } else {
                            Text("无法预览该图片（可能已损坏或被清理）", fontSize = 12.sp)
                        }
                        Text(
                            "${att.kind.display} · ${att.sizeLabel}\n发送时会压缩并移除位置等元数据。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            )
        }

        AttachKind.PDF -> {
            var start by remember { mutableStateOf((selectedPages?.minOrNull() ?: 1).toString()) }
            var end by remember {
                mutableStateOf(
                    (selectedPages?.maxOrNull() ?: minOf(meta?.pdfPageCount ?: 1, 20)).toString(),
                )
            }
            var error by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("选择 PDF 页") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            meta?.error != null -> Text("无法读取 PDF：${meta.error}", fontSize = 12.sp)
                            meta?.pdfPageCount == null -> Text("正在读取页数…", fontSize = 12.sp)
                            else -> Text("共 ${meta.pdfPageCount} 页；单次最多分析 20 页文本或 6 页扫描图。", fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = start,
                                onValueChange = { start = it.filter { c -> c.isDigit() }.take(4); error = null },
                                modifier = Modifier.weight(1f),
                                label = { Text("起始页") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = end,
                                onValueChange = { end = it.filter { c -> c.isDigit() }.take(4); error = null },
                                modifier = Modifier.weight(1f),
                                label = { Text("结束页") },
                                singleLine = true,
                            )
                        }
                        error?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                        Text(
                            "默认只分析前 20 页；超出会提示分批。扫描页会渲染成图片发给视觉模型。",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val total = meta?.pdfPageCount
                        if (total == null) {
                            error = "页数尚未读取完成"
                            return@TextButton
                        }
                        val a = start.toIntOrNull()
                        val b = end.toIntOrNull()
                        if (a == null || b == null || a < 1 || b < a || b > total) {
                            error = "请输入有效范围（1–$total）"
                        } else if (b - a + 1 > 20) {
                            error = "单次最多 20 页，请缩小范围或分批发送"
                        } else {
                            onSetPages((a..b).toSet())
                            onDismiss()
                        }
                    }) { Text("确定") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            onSetPages(emptySet())
                            onDismiss()
                        }) { Text("重置默认") }
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                },
            )
        }

        AttachKind.XLSX -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("选择工作表") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            meta?.error != null -> Text("无法读取表格：${meta.error}", fontSize = 12.sp)
                            meta == null || meta.sheets.isEmpty() -> Text("正在读取工作表…", fontSize = 12.sp)
                            else -> meta.sheets.forEach { name ->
                                FilterChip(
                                    selected = (selectedSheet ?: meta.sheets.first()) == name,
                                    onClick = { onSetSheet(if (name == meta.sheets.first() && selectedSheet == null) null else name) },
                                    label = { Text(name, fontSize = 11.sp) },
                                )
                            }
                        }
                        Text(
                            "默认读取第一个工作表；单次最多 2000 个有值单元格。",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
            )
        }

        else -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(att.fileName) },
                text = {
                    Text(
                        "${att.kind.display} · ${att.sizeLabel}\n文档在手机本地解析为文本后发送，原文件不上传到文件存储接口。",
                        fontSize = 12.sp,
                    )
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            )
        }
    }
}

// ---------- 编辑对话框 ----------

@Composable
private fun ActionEditDialog(
    action: AiAction,
    onSave: (AiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(action.title) }
    var d1 by remember {
        mutableStateOf(action.startDate ?: action.dueDate ?: action.targetDate ?: "")
    }
    var d2 by remember { mutableStateOf(action.endDate ?: action.startDate ?: "") }
    var t1 by remember { mutableStateOf(action.startTime ?: action.dueTime ?: "") }
    var t2 by remember { mutableStateOf(action.endTime ?: "") }
    var location by remember { mutableStateOf(action.location) }
    var error by remember { mutableStateOf<String?>(null) }

    fun validate(): Boolean {
        val problem = com.jsy.dayplan.ai.AiDraftEditRules.validate(
            action.op, title, d1, t1, t2,
        )
        error = problem
        return problem == null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑「${OP_LABELS[action.op] ?: action.op}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") })
                OutlinedTextField(
                    value = d1, onValueChange = { d1 = it },
                    label = {
                        Text(
                            when {
                                action.op.endsWith("schedule") -> "开始日期"
                                action.op.endsWith("todo") -> "截止日期（可清空）"
                                else -> "目标日期"
                            },
                        )
                    },
                )
                if (action.op.endsWith("schedule")) {
                    OutlinedTextField(value = d2, onValueChange = { d2 = it }, label = { Text("结束日期（默认同开始）") })
                    OutlinedTextField(value = t1, onValueChange = { t1 = it }, label = { Text("开始时间 HH:mm") })
                    OutlinedTextField(value = t2, onValueChange = { t2 = it }, label = { Text("结束时间 HH:mm") })
                    OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("地点") })
                }
                if (action.op == "create_todo" || action.op == "update_todo") {
                    OutlinedTextField(
                        value = t1,
                        onValueChange = { t1 = it },
                        label = { Text("截止时间 HH:mm（可清空）") },
                        supportingText = {
                            if (d1.isBlank()) Text("截止日期留空时，将保存为未定日期")
                        },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Text(
                    "提醒与分类沿用草稿；详细设置可在保存后进入条目编辑页调整。",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (validate()) {
                    onSave(
                        action.copy(
                            title = title.trim(),
                            startDate = if (action.op.endsWith("schedule")) d1.trim() else action.startDate,
                            endDate = if (action.op.endsWith("schedule")) (d2.ifBlank { d1 }).trim() else action.endDate,
                            startTime = if (action.op.endsWith("schedule") && !action.allDay) {
                                t1.trim().takeIf { it.isNotBlank() }
                            } else action.startTime,
                            endTime = if (action.op.endsWith("schedule") && !action.allDay) {
                                t2.trim().takeIf { it.isNotBlank() }
                            } else action.endTime,
                            dueDate = if (action.op.endsWith("todo")) d1.trim().ifBlank { null } else action.dueDate,
                            dueTime = if (action.op.endsWith("todo")) {
                                if (d1.isBlank()) null else t1.trim().ifBlank { null }
                            } else action.dueTime,
                            targetDate = if (action.op.endsWith("countdown")) d1.trim() else action.targetDate,
                            location = location.trim(),
                        ),
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
