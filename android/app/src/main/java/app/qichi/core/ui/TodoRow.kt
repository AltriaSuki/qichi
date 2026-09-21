package app.qichi.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.CheckCircle
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.sync.Local
import app.qichi.shared.api.Todo
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一行待办（Plan.dc.html 的待办区）：圆形复选框、标题、右侧截止与指派的人（空 = 两个人叠放）。
 * 子任务缩进、复选框和字都小一号；完成的用 faint 色；待发送显示小时钟，发送失败用 accent 标出。
 */
@Composable
fun TodoRow(
    item: Local<Todo>,
    people: People,
    today: LocalDate,
    zone: ZoneId,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtask: Boolean = false,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val todo = item.value
    val done = todo.doneAt != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (subtask) 40.dp else 50.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (subtask) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 44dp 触控区，视觉上让圆圈贴着页边
        CheckCircle(
            checked = done,
            onCheckedChange = onToggle,
            size = if (subtask) 16.dp else 20.dp,
            contentDescription = if (done) "取消完成" else "完成",
            modifier = Modifier.offset(x = (-12).dp),
        )
        Text(
            text = todo.title,
            style = (if (subtask) type.body.copy(fontSize = 14.sp) else type.bodyLarge).copy(
                color = if (done) colors.faint else colors.ink,
                textDecoration = if (done) TextDecoration.None else null,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .offset(x = (-4).dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.isPending) Icon(QichiIcons.Clock, contentDescription = "待发送", tint = colors.muted, modifier = Modifier.size(13.dp))
            if (item.isFailed) Text("发送失败", style = type.caption.copy(color = colors.accent))
            if (todo.recurrence != null) Icon(QichiIcons.Repeat, contentDescription = "重复", tint = colors.muted, modifier = Modifier.size(14.dp))
            val due = todo.dueDate ?: todo.dueAt?.atZone(zone)?.toLocalDate()
            if (due != null && !subtask) {
                val (label, numeral) = relativeDay(due, today)
                val overdue = !done && due.isBefore(today)
                val color = if (overdue) colors.accent else colors.muted
                Text(label, style = if (numeral) type.numeral.copy(fontSize = 16.sp, color = color) else type.caption.copy(color = color))
            }
            if (!subtask) {
                val assignee = todo.assigneeId
                if (assignee == null) {
                    val both = listOfNotNull(people.me, people.partner).map { people.markChar(it.userId) to people.person(it.userId) }
                    if (both.size == 2) PersonMarks(both) else Unit
                } else {
                    PersonMark(people.markChar(assignee), people.person(assignee), size = 18.dp)
                }
            }
        }
    }
}
