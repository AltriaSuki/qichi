package app.qichi.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.FeatureTile
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.dashedBorder
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.shared.api.AiAction
import app.qichi.shared.api.SummarySource
import app.qichi.shared.model.AiActionKind
import app.qichi.shared.model.AiActionStatus
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.model.wireName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** AI 提议卡片上的操作：好、不用、打开建好的那条。 */
internal class AiActionHandlers(
    val onAccept: (AiAction) -> Unit,
    val onDismiss: (AiAction) -> Unit,
    /** 打开建好的实体（复用「打开来源」的跳转） */
    val onOpen: (SummarySource) -> Unit,
)

private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun day(d: LocalDate) = "${d.monthValue}月${d.dayOfMonth}日 ${weekdays[d.dayOfWeek.value - 1]}"

private fun time(t: ZonedDateTime) = day(t.toLocalDate()) + " %02d:%02d".format(t.hour, t.minute)

private fun kindLabel(a: AiAction) = when (a.kind) {
    AiActionKind.Event -> "日程"
    AiActionKind.Todo -> "待办"
    AiActionKind.Idea -> "灵感"
    AiActionKind.ArchiveItem -> "档案 · " + when (a.draft.archiveKind) {
        ArchiveKind.Preference -> "偏好"
        ArchiveKind.Boundary -> "界限"
        ArchiveKind.Concern -> "顾虑"
        ArchiveKind.Milestone -> "纪念"
        ArchiveKind.Decision -> "决定"
        ArchiveKind.Consensus, null -> "共识"
    }
}

/** 卡片第一行：类别和时间。 */
private fun headline(a: AiAction, zone: ZoneId): String {
    val d = a.draft
    val whenText = when (a.kind) {
        AiActionKind.Event -> if (d.allDay) {
            d.startDate?.let { s -> day(s) + (d.endDate?.takeIf { it != s }?.let { " — " + day(it) } ?: "") + " 全天" }
        } else {
            d.startsAt?.let { time(it.atZone(zone)) }
        }
        AiActionKind.Todo -> d.dueAt?.let { time(it.atZone(zone)) + " 前" } ?: d.dueDate?.let { day(it) + " 前" }
        else -> null
    }
    return listOfNotNull(kindLabel(a), whenText).joinToString(" · ")
}

/** 卡片第三行：地点、给谁、属于计划、备注。 */
private fun details(a: AiAction, people: People): String? {
    val d = a.draft
    return listOfNotNull(
        d.location?.let { "在$it" },
        d.assigneeId?.let { "给${people.name(it)}" },
        d.planId?.let { "加进计划" },
        d.note,
    ).joinToString(" · ").ifEmpty { null }
}

/** 建好的实体当作「来源」打开：日程跳到那一天，其它跳到对应页。 */
private fun resultSource(a: AiAction, zone: ZoneId): SummarySource? {
    val id = a.resultId ?: return null
    val at = a.draft.startsAt ?: a.draft.startDate?.atStartOfDay(zone)?.toInstant() ?: Instant.now()
    return SummarySource(0, a.kind.wireName, id, a.draft.title, at)
}

/**
 * AI 回答下面的提议卡片（P8-02）：点「好」才由服务端建成日程 / 待办 / 档案 / 灵感，点「不用」收起。
 * 建好的显示「已记下 · 查看」；不用了的不再显示。两个人看到的是同一组卡片，谁点都行。
 */
@Composable
internal fun AiActionCards(actions: List<AiAction>, people: People, zone: ZoneId, handlers: AiActionHandlers) {
    val visible = actions.filter { it.status != AiActionStatus.Dismissed }
    if (visible.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        visible.forEach { AiActionCard(it, people, zone, handlers) }
    }
}

/** 提议的功能色块：日程 = 日历，待办、灵感、档案用各自的图标（颜色统一雾蓝，是 AI 的颜色）。 */
private fun kindIcon(a: AiAction) = when (a.kind) {
    AiActionKind.Event -> QichiIcons.Calendar
    AiActionKind.Todo -> QichiIcons.Todo
    AiActionKind.Idea -> QichiIcons.Idea
    AiActionKind.ArchiveItem -> QichiIcons.Archive
}

/** 一张提议（按 New-Chat）：雾蓝虚线框 + 淡底，左边功能色块，中间标题和时间，右边「不用」「好」。 */
@Composable
private fun AiActionCard(a: AiAction, people: People, zone: ZoneId, handlers: AiActionHandlers) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth()
            .dashedBorder(colors.personB.copy(alpha = .45f), 12.dp)
            .background(colors.personB.copy(alpha = .06f), shape)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FeatureTile(kindIcon(a), colors.personB, size = 34.dp)
        Column(Modifier.weight(1f)) {
            Text(a.draft.title, style = type.body.copy(fontWeight = FontWeight.W500, lineHeight = 21.75.tsp, color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(headline(a, zone), style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
            details(a, people)?.let { Text(it, style = type.caption.copy(fontSize = 12.tsp, color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (a.status == AiActionStatus.Accepted) {
                Text(a.decidedBy?.let { "${people.name(it)}记下了" } ?: "已记下", style = type.caption.copy(fontSize = 12.tsp, color = colors.personB))
            }
        }
        if (a.status == AiActionStatus.Accepted) {
            resultSource(a, zone)?.let { src -> TextAction("查看", { handlers.onOpen(src) }) }
        } else {
            TextAction("不用", { handlers.onDismiss(a) }, color = colors.muted)
            Box(
                Modifier.heightIn(min = 40.dp).clip(QichiShapes.pill).background(colors.ink)
                    .clickable(role = Role.Button, onClickLabel = "记下这${if (a.kind == AiActionKind.Event) "个日程" else "条"}") { handlers.onAccept(a) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("好", style = type.button.copy(color = colors.background))
            }
        }
    }
}
