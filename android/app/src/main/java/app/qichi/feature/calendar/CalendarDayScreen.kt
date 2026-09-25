package app.qichi.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.chinese
import java.time.LocalDate
import java.util.UUID

@Composable
fun CalendarDayScreen(
    roomId: UUID,
    date: LocalDate,
    onBack: () -> Unit,
    vm: CalendarDayViewModel = hiltViewModel<CalendarDayViewModel, CalendarDayViewModel.Factory>(key = "$roomId-$date") { it.create(roomId, date) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        ItemTopBar("${state.date.monthValue} 月 ${state.date.dayOfMonth} 日", onBack, feature = Feature.Calendar)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.date.dayOfWeek.chinese, style = QichiTheme.typography.pageTitle.copy(color = colors.ink))
                Text(" ${state.date.dayOfMonth}", style = QichiTheme.typography.numeral.copy(fontSize = 28.tsp, color = colors.ink))
                Spacer(Modifier.weight(1f))
                val marks = listOfNotNull(state.people.me, state.people.partner)
                    .map { state.people.markChar(it.userId) to state.people.person(it.userId) }
                if (marks.isNotEmpty()) PersonMarks(marks)
            }
            Text("%d.%02d".format(state.date.year, state.date.monthValue),
                style = QichiTheme.typography.numeral.copy(fontSize = 17.tsp, color = colors.muted),
                modifier = Modifier.padding(top = Spacing.xs))
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.l),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction("今天", vm::today, color = colors.muted)
                IconAction(QichiIcons.ChevronLeft, "前一天", { vm.move(-1) })
                IconAction(QichiIcons.ChevronRight, "后一天", { vm.move(1) })
            }
            if (state.items.hasContent) {
                SectionLabel("${state.date.dayOfWeek.chinese} · ${state.date.dayOfMonth}")
                MistCard(Modifier.padding(top = Spacing.s)) {
                    CalendarAgenda(state.items, state.people, state.zone)
                }
            }
        }
    }
}
