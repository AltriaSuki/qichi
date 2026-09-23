package app.qichi.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 「3 · ix」这样的日期写法（设计稿里的里程碑、记录）。 */
fun shortDate(date: LocalDate): String = "${date.dayOfMonth} · ${monthRoman(date.monthValue)}"

/** 选日期：不设 / 今天 / 明天 / 选日期。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateChoice(label: String?, selected: LocalDate?, today: LocalDate, onSelect: (LocalDate?) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var picking by remember { mutableStateOf(false) }
    Column {
        if (label != null) {
            SectionLabel(label) {
                selected?.let { Text(relativeDay(it, today).first, style = type.caption.copy(color = colors.accent)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoicePill("不设", selected == null, { onSelect(null) }, Modifier.weight(1f))
            ChoicePill("今天", selected == today, { onSelect(today) }, Modifier.weight(1f))
            ChoicePill("明天", selected == today.plusDays(1), { onSelect(today.plusDays(1)) }, Modifier.weight(1f))
            val other = selected != null && selected != today && selected != today.plusDays(1)
            ChoicePill(if (other) shortDate(selected!!) else "选日期", other, { picking = true }, Modifier.weight(1f))
        }
    }
    if (picking) {
        val initial = (selected ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextAction("确定", {
                    dateState.selectedDateMillis?.let { onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                })
            },
            dismissButton = { TextAction("取消", { picking = false }, color = colors.muted) },
        ) {
            DatePicker(state = dateState, title = null, headline = null, showModeToggle = false)
        }
    }
}
