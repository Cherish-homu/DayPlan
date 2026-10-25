package com.jsy.dayplan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.ai.AiConfig
import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.vm.AiViewModel

/**
 * AI 助手设置：Key（加密、掩码、可清除）、连接测试、模型与图片能力、
 * 自动保存、规划偏好、入口显示与素材署名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(vm: AiViewModel, onBack: () -> Unit) {
    val version by vm.configVersion.collectAsState()
    val cfg = remember(version) { vm.config() }
    val maskedKey = remember(version) { vm.maskedKey() }

    var keyInput by remember { mutableStateOf("") }
    var keyMsg by remember { mutableStateOf<String?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }

    var durationText by remember(version) { mutableStateOf(cfg.defaultDurationMinutes.toString()) }
    var windowStart by remember(version) { mutableStateOf(cfg.planWindowStart) }
    var windowEnd by remember(version) { mutableStateOf(cfg.planWindowEnd) }

    fun update(next: AiConfig) = vm.saveConfig(next)
    val models = remember(version) { vm.availableModels() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 助手设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 启用与说明
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启用 AI 助手", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "关闭后右下角入口与聊天页只提示去设置，离线功能照常。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = cfg.enabled,
                            onCheckedChange = { update(cfg.copy(enabled = it, noticeAcknowledged = true)) },
                        )
                    }
                    Text(
                        "使用你本人的 DeepSeek API Key 直连官方服务（https://api.deepseek.com）。" +
                            "点击发送时，本次聊天文字与所选附件（图片、文档提取文本/扫描页图像）会发送到 DeepSeek；" +
                            "开启“参考现有安排”时还会发送所选日期范围的忙碌时间摘要与候选条目标题/ID/版本。" +
                            "默认不发送备注、地点、完整数据库或备份。费用由你的 DeepSeek 账户按实际调用结算。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Key
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("DeepSeek API Key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (maskedKey != null) "当前：$maskedKey（本机加密存储，不参与备份）" else "未配置",
                        fontSize = 12.sp,
                        color = if (maskedKey != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.tertiary,
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it.trim(); keyMsg = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (maskedKey != null) "替换 Key" else "填写 Key（sk-…）") },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val (ok, msg) = vm.saveKey(keyInput)
                                keyMsg = msg
                                if (ok) keyInput = ""
                            },
                            enabled = keyInput.isNotBlank(),
                        ) { Text("保存") }
                        OutlinedButton(onClick = { vm.clearKey(); keyMsg = "已清除本机 Key"; keyInput = "" }) {
                            Text("清除")
                        }
                        OutlinedButton(
                            onClick = {
                                testing = true
                                testMsg = "正在连接…"
                                vm.testConnection { ok, msg ->
                                    testing = false
                                    testMsg = (if (ok) "✓ " else "✗ ") + msg
                                }
                            },
                            enabled = !testing && (maskedKey != null || keyInput.isNotBlank()),
                        ) { Text(if (testing) "测试中…" else "测试连接") }
                    }
                    keyMsg?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
                    testMsg?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "测试连接只调用 GET /models，不产生对话费用；Key 不写入日志、聊天记录或 JSON 备份。" +
                            "卸载或换机后需重新填写。",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 模型
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "图片需要支持 image 输入的模型；纯文本模型不能接收图片。实际能力以官方 GET /models 为准。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    models.forEach { m ->
                        val mods = if (m.modalities.isEmpty()) "能力未知" else m.modalities.joinToString("/")
                        FilterChip(
                            selected = cfg.model == m.id,
                            onClick = { update(cfg.copy(model = m.id)) },
                            label = { Text("${m.id}（$mods）", fontSize = 11.sp) },
                        )
                    }
                    if (models.none { it.id == cfg.model }) {
                        Text("当前模型：${cfg.model}", fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            vm.refreshModalities()
                            testMsg = "已请求刷新模型列表（需要有效 Key）"
                        }) { Text("刷新模型能力") }
                    }
                    Text(
                        "支持图像输入的模型：${
                            if (vm.modalitiesOf(cfg.model)?.contains("image") == true) "是" else
                                if (vm.modalitiesOf(cfg.model) == null) "未知" else "否（发图片会提示切换）"
                        }",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 保存与偏好
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("保存与规划偏好", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("明确新增时自动保存", fontSize = 13.sp)
                            Text(
                                "仅当整批都是明确新增、时间完整、无冲突且通过本地校验时自动保存，" +
                                    "并在聊天中显示条目与“撤销本批”。附件批次始终先预览。",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = cfg.autoSave,
                            onCheckedChange = { update(cfg.copy(autoSave = it)) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("参考现有安排", fontSize = 13.sp)
                            Text(
                                "发送未来 7 天忙碌时间摘要与近期候选条目的标题/ID/版本；本地冲突检查始终执行。",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = cfg.sendExistingSummary,
                            onCheckedChange = { update(cfg.copy(sendExistingSummary = it)) },
                        )
                    }
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { t ->
                            durationText = t.filter { it.isDigit() }.take(3)
                            durationText.toIntOrNull()?.let { n ->
                                if (n in 10..240) update(vm.config().copy(defaultDurationMinutes = n))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("默认时长（分钟，10–240）") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = windowStart,
                            onValueChange = { t ->
                                windowStart = t
                                PlanRules.normalizeTime(t)?.let { n ->
                                    update(vm.config().copy(planWindowStart = n))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("可安排开始") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = windowEnd,
                            onValueChange = { t ->
                                windowEnd = t
                                PlanRules.normalizeTime(t)?.let { n ->
                                    update(vm.config().copy(planWindowEnd = n))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("可安排结束") },
                            singleLine = true,
                        )
                    }
                }
            }

            // 入口
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("入口显示", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("显示右下角 DeepSeek 娘入口", fontSize = 13.sp)
                            Text(
                                "展开约 80×120dp、收起约 60dp；长按头像可暂时隐藏，在此恢复。",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = cfg.showWhale,
                            onCheckedChange = { update(cfg.copy(showWhale = it)) },
                        )
                    }
                }
            }

            // 演示模式（仅 debug 包可用）
            if (vm.mockAvailable()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("演示（Mock）模式", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("使用内置演示计划，不连接网络", fontSize = 13.sp)
                                Text(
                                    "演示结果会在聊天中明确标注“Mock”，不会伪装成已连通 DeepSeek。",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = vm.isMockActive(),
                                onCheckedChange = { vm.setMockMode(it) },
                            )
                        }
                    }
                }
            }

            // 数据与素材
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("数据与缓存", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("清空对话及附件缓存") }
                    Text(
                        "聊天记录、待确认草稿与附件副本保存在应用私有目录，不进入业务 JSON 备份；" +
                            "已保存的日程不受清空影响。附件缓存默认 7 天未被引用即清理。",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("第三方素材", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "聊天入口头像使用社区二创 Q 版“DeepSeek 娘”：YunYueSama / codex-deepseek-pet" +
                            "（commit 7661c8b），采用“大肥鱼项目署名许可 1.0”，非 DeepSeek 官方形象。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showLicense = true }) { Text("查看素材许可与修改说明") }
                }
            }

            Text(
                "日序 v1.5 · AI 为可选功能，需自备 Key；离线功能不依赖网络",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空对话及附件缓存？") },
            text = { Text("将删除聊天记录、待确认计划与本应用缓存的所有附件副本；已保存的日程不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    vm.clearConversation()
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
        )
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("DeepSeek 娘素材许可") },
            text = {
                Text(
                    "素材：codex-deepseek-pet 的 assets/whale 成品立绘（portrait.png / idle-front.png）。\n" +
                        "作者：YunYueSama\n" +
                        "来源：https://github.com/YunYueSama/codex-deepseek-pet\n" +
                        "commit：7661c8b304c5400701f91da01b1a643a207331de\n" +
                        "许可：大肥鱼项目署名许可 1.0（非 MIT）。\n\n" +
                        "本应用仅使用成品立绘，未搬运参考图、字体、Electron 程序或桌宠插件代码；" +
                        "为入口尺寸做了裁剪与缩放（修改），并打包为本地图片，显示时不联网。\n" +
                        "该形象为社区二创，非 DeepSeek 官方产品形象；个人自用，不据此推断底层第三方设计均获商用授权。",
                    fontSize = 11.sp,
                )
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("知道了") } },
        )
    }
}
