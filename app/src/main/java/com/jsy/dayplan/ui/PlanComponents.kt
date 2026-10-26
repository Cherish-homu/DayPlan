package com.jsy.dayplan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.CountdownCategory
import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.core.ScheduleCategory
import com.jsy.dayplan.core.ScheduleLogic
import com.jsy.dayplan.core.TodayLogic
import com.jsy.dayplan.core.TodoDisplay
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.time.LocalDate
import java.time.LocalTime

/** 三类条目的统一名称、解释和图标；新增面板与编辑页共用，避免文案分叉。 */
enum class PlanItemType(
    val label: String,
    val hint: String,
    val example: String,
    val icon: ImageVector,
) {
    SCHEDULE("日程", "占一段时间", "例：明天 15:00–16:00 开会", Icons.Outlined.Schedule),
    TODO("待办", "完成一件事", "例：周五前交报告，做完就勾掉", Icons.Outlined.CheckBoxOutlineBlank),
    COUNTDOWN("倒数日", "期待或纪念某一天", "例：距离生日还有 12 天", Icons.Outlined.Event),
}

@Composable
fun PlanItemType.markColor(): Color = when (this) {
    PlanItemType.SCHEDULE -> MaterialTheme.colorScheme.primary
    PlanItemType.TODO -> todoMarkColor()
    PlanItemType.COUNTDOWN -> countdownMarkColor()
}

/** 页面左右边距：常规 20dp，360dp 窄屏 16dp。 */
@Composable
fun pageHorizontalPadding(): androidx.compose.ui.unit.Dp =
    if (LocalConfiguration.current.screenWidthDp < 380) 16.dp else 20.dp

@Composable
fun TypeBadge(type: PlanItemType, modifier: Modifier = Modifier) {
    val color = type.markColor()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(type.icon, null, Modifier.size(13.dp), tint = color)
            Text(type.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

/** 月历格子标记：短线=日程、空心/实心小方块=待办、菱形=倒数日；同时用形状冗余。 */
@Composable
fun CalendarTypeMark(
    type: PlanItemType,
    color: Color,
    filled: Boolean = false,
) {
    when (type) {
        PlanItemType.SCHEDULE -> Box(
            Modifier.width(9.dp).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(color),
        )

        PlanItemType.TODO -> Box(
            Modifier
                .size(5.5.dp)
                .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, color)),
        )

        PlanItemType.COUNTDOWN -> Box(
            Modifier
                .size(4.5.dp)
                .rotate(45f)
                .background(color)
                .clip(RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    addLabel: String? = null,
    onAdd: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        if (addLabel != null && onAdd != null) {
            TextButton(
                onClick = onAdd,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(addLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(actionLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun CollapsibleGroupHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$label $count 项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 日程行：时间在左，分类降为小标签；跨天仍显示今天可见的时刻。
 * 无完成框，避免与“待办”混淆。
 */
@Composable
fun ScheduleTimelineRow(
    schedule: ScheduleEntity,
    today: LocalDate,
    now: LocalTime?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startDate = runCatching { LocalDate.parse(schedule.startDate) }.getOrNull()
    val endDate = runCatching { LocalDate.parse(schedule.endDate) }.getOrNull()
    val startMin = if (schedule.allDay) 0 else
        if (startDate != null && startDate.isBefore(today)) 0
        else ScheduleLogic.toMinutes(schedule.startTime ?: "00:00") ?: 0
    val endMin = if (schedule.allDay) 24 * 60 else
        if (endDate != null && endDate.isAfter(today)) 24 * 60
        else ScheduleLogic.toMinutes(schedule.endTime ?: "24:00") ?: 24 * 60
    val nowMin = now?.let { it.hour * 60 + it.minute }
    val ongoing = nowMin != null && nowMin in startMin until endMin
    val ended = nowMin != null && nowMin >= endMin
    val statusColor = when {
        ongoing -> MaterialTheme.colorScheme.primary
        ended -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .alpha(if (ended) 0.62f else 1f)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(62.dp).padding(top = 1.dp)) {
            val leftStart = if (!schedule.allDay && startDate != null && startDate.isBefore(today)) {
                "00:00"
            } else schedule.startTime ?: "--:--"
            val leftEnd = if (!schedule.allDay && endDate != null && endDate.isAfter(today)) {
                "24:00"
            } else schedule.endTime
            Text(
                if (schedule.allDay) "全天" else leftStart,
                fontSize = if (schedule.allDay) 15.sp else 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (ongoing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (!schedule.allDay && leftEnd != null) {
                Text(
                    "到 $leftEnd",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Canvas(Modifier.width(14.dp).height(44.dp)) {
            val x = size.width / 2f
            drawLine(
                color = statusColor.copy(alpha = 0.5f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
            drawCircle(
                color = statusColor,
                radius = if (ongoing) 6f else 4.5f,
                center = Offset(x, 16f),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                schedule.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                )
                Text(
                    ScheduleCategory.NAMES.getOrElse(schedule.category) { "其他" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (schedule.location.isNotBlank()) {
                    Text(
                        "· ${schedule.location}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (schedule.startDate != schedule.endDate) {
                Text(
                    TodayLogic.scheduleTimeText(schedule, today),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                ongoing && !schedule.allDay -> Text(
                    "进行中",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )

                ended && !schedule.allDay -> Text(
                    "已结束",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 待办行：真实完成框是第一识别，日期只作辅助；逾期用短标签而非整行红底。 */
@Composable
fun TodoRow(
    todo: TodoEntity,
    today: LocalDate,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val due = TodoDisplay.dueLine(todo, today)
    val overdue = TodoDisplay.overdueDays(todo, today) != null
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = todo.done,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = todoMarkColor(),
                checkmarkColor = MaterialTheme.colorScheme.surface,
                uncheckedColor = todoMarkColor().copy(alpha = 0.72f),
            ),
            modifier = Modifier.size(48.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onEdit)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    todo.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                    color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (todo.priority == 1) {
                    Icon(
                        Icons.Filled.Star,
                        "重要",
                        Modifier.padding(start = 4.dp).size(14.dp),
                        tint = countdownMarkColor(),
                    )
                }
            }
            if (due != null) {
                Text(
                    due,
                    fontSize = 12.sp,
                    color = when {
                        overdue -> MaterialTheme.colorScheme.error
                        !todo.done && todo.dueDate == today.toString() ->
                            MaterialTheme.colorScheme.primary

                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 首页横向“重要日子”卡：宽度固定，标题最多两行，天数独立突出。 */
@Composable
fun CountdownMiniCard(
    countdown: CountdownEntity,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val occ = CountdownLogic.nextOccurrence(
        LocalDate.parse(countdown.targetDate), today, countdown.yearlyRepeat,
    )
    val days = CountdownLogic.daysBetween(today, occ)
    val number = when {
        days == 0L -> "今天"
        days > 0 -> days.toString()
        else -> (-days).toString()
    }
    GlassCard(
        modifier = modifier
            .width(156.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp).heightIn(min = 112.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CalendarTypeMark(PlanItemType.COUNTDOWN, countdownMarkColor())
                Spacer(Modifier.width(6.dp))
                Text(
                    CountdownCategory.NAMES.getOrElse(countdown.category) { "其他" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (countdown.pinned) {
                    Spacer(Modifier.width(4.dp))
                    Text("置顶", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                countdown.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    number,
                    fontSize = if (days == 0L) 24.sp else 32.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = countdownMarkColor(),
                )
                if (days != 0L) {
                    Text(
                        if (days > 0) " 天" else " 天前",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            Text(
                "${occ.monthValue}月${occ.dayOfMonth}日" +
                    if (countdown.yearlyRepeat) " · 每年" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 中性空态：说清具体情境，并保留明确动作。 */
@Composable
fun EmptyHintCard(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, fontSize = 13.sp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemSheet(
    contextLabel: String? = null,
    onDismiss: () -> Unit,
    onSelect: (PlanItemType) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text("新建一项", style = MaterialTheme.typography.titleLarge)
            if (contextLabel != null) {
                Text(
                    contextLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            PlanItemType.entries.forEachIndexed { index, type ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onSelect(type) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = type.markColor().copy(alpha = 0.12f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(type.icon, null, Modifier.size(22.dp), tint = type.markColor())
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(type.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                " · ${type.hint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            type.example,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


@Composable
fun NotFoundScreen(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onBack) { Text("返回") }
        }
    }
}


/**
 * 页面顶栏：标题保持单行固定高度，副标题独立成行，随字体缩放向下增长，
 * 不再被 TopAppBar 的 64dp 高度裁切。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTopBar(
    title: String,
    subtitle: String? = null,
    titleSize: TextUnit = 30.sp,
    titleLineHeight: TextUnit = 36.sp,
    subtitleStartPadding: androidx.compose.ui.unit.Dp = 16.dp,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth(),
    ) {
        TopAppBar(
            title = {
                Text(
                    title,
                    fontSize = titleSize,
                    lineHeight = titleLineHeight,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            },
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(
                    start = subtitleStartPadding,
                    end = 16.dp,
                    bottom = 8.dp,
                ),
            )
        }
    }
}
