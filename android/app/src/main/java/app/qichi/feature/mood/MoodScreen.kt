package app.qichi.feature.mood

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.MoodRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.CheckCircle
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.color
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.component.decor.ruledPaper
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.displayName
import app.qichi.core.ui.feelingWord
import app.qichi.core.ui.icon
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.MoodReplyKind
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 正在填写的这一条心情。 */
data class MoodDraft(
    val label: MoodLabel? = null,
    val intensity: Int = 5,
    val note: String = "",
    val needsComfort: Boolean = false,
) {
    val canSave: Boolean get() = label != null
}

data class MoodUiState(
    val people: People = People.Empty,
    val draft: MoodDraft = MoodDraft(),
    /** 对方最近的一条心情 */
    val partnerMood: Local<Mood>? = null,
    /** 我对它已经给出的回应 */
    val myRepliesToPartner: List<MoodReply> = emptyList(),
    /** 我最近的一条心情 */
    val myMood: Local<Mood>? = null,
    /** 对方对我最近这条的回应 */
    val repliesToMe: List<MoodReply> = emptyList(),
)

@HiltViewModel(assistedFactory = MoodViewModel.Factory::class)
class MoodViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val moods: MoodRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val draft = MutableStateFlow(MoodDraft())

    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members ->
        People(room, members, session.currentUserId)
    }

    val state: StateFlow<MoodUiState> = combine(people, moods.observeMoods(roomId), moods.observeReplies(roomId), draft) { p, all, replies, d ->
        val me = p.myUserId
        val partnerMood = all.firstOrNull { it.value.authorId != me }
        val myMood = all.firstOrNull { it.value.authorId == me }
        MoodUiState(
            people = p,
            draft = d,
            partnerMood = partnerMood,
            myRepliesToPartner = replies.map { it.value }.filter { it.moodId == partnerMood?.value?.id && it.authorId == me },
            myMood = myMood,
            repliesToMe = replies.map { it.value }.filter { it.moodId == myMood?.value?.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoodUiState())

    fun onLabel(label: MoodLabel) = draft.update { it.copy(label = label) }
    fun onIntensity(value: Int) = draft.update { it.copy(intensity = value) }
    fun onNote(value: String) = draft.update { it.copy(note = value.take(Limits.MOOD_NOTE_MAX)) }
    fun onComfort(value: Boolean) = draft.update { it.copy(needsComfort = value) }

    fun save() {
        val d = draft.value
        val label = d.label ?: return
        draft.value = MoodDraft()
        viewModelScope.launch { moods.record(roomId, label, d.intensity, d.note, d.needsComfort) }
    }

    /** 点一下给出回应，再点一下收回。 */
    fun toggleReply(kind: MoodReplyKind) {
        val s = state.value
        val mood = s.partnerMood?.value ?: return
        viewModelScope.launch {
            val existing = s.myRepliesToPartner.firstOrNull { it.kind == kind }
            if (existing != null) moods.withdraw(existing) else moods.respond(mood, kind)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): MoodViewModel
    }
}

/** 心情页（按 New-Mood）：此刻（八个带图标的格子）/ 强度（十个点）/ 几句（横线纸）/ 需要安慰 + 记下；下面是对方和自己最近的心情卡。 */
@Composable
fun MoodScreen(
    roomId: UUID,
    onBack: () -> Unit,
    viewModel: MoodViewModel = hiltViewModel<MoodViewModel, MoodViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val draft = state.draft
    val zone = remember { ZoneId.systemDefault() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        FeatureTopBar(Feature.Mood, onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.page, end = Spacing.page, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
        ) {
            Column {
                SectionLabel("此刻")
                MoodGrid(draft.label, viewModel::onLabel)
            }
            Column {
                SectionLabel("强度") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${draft.intensity}", style = type.numeral.copy(fontSize = 22.tsp, fontWeight = FontWeight.W500, color = colors.personA))
                        Text("/10", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted), modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                IntensityPicker(draft.intensity, viewModel::onIntensity)
            }
            Column {
                SectionLabel("几句")
                val shape = RoundedCornerShape(12.dp)
                BasicTextField(
                    value = draft.note,
                    onValueChange = viewModel::onNote,
                    textStyle = type.body.copy(lineHeight = 26.tsp, color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 78.dp)
                        .lift(colors, shape)
                        .clip(shape)
                        .ruledPaper((26 * type.scale).dp, top = 9.dp, margin = false)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .semantics { contentDescription = "几句" },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Sizes.touchTarget)
                        .toggleable(value = draft.needsComfort, role = Role.Checkbox, onValueChange = viewModel::onComfort),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    CheckCircle(checked = draft.needsComfort, onCheckedChange = null)
                    Text("需要安慰", style = type.body.copy(color = colors.ink))
                }
                PrimaryButton("记下", onClick = viewModel::save, enabled = draft.canSave)
            }

            state.partnerMood?.let { mood ->
                Column(Modifier.padding(top = 4.dp)) {
                    SectionLabel(state.people.name(mood.value.authorId)) { MoodTime(mood.value, zone) }
                    MoodCard(mood.value, state.people) {
                        FlowRow(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MoodReplyKind.entries.forEach { kind ->
                                val given = state.myRepliesToPartner.any { it.kind == kind }
                                Pill(kind.displayName, onClick = { viewModel.toggleReply(kind) }, selected = given)
                            }
                        }
                    }
                }
            }

            state.myMood?.let { mood ->
                Column {
                    SectionLabel("我") { MoodTime(mood.value, zone) }
                    MoodCard(mood.value, state.people, pending = mood.isPending) {
                        state.repliesToMe.forEach { reply ->
                            Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PersonMark(state.people.markChar(reply.authorId), state.people.person(reply.authorId), size = 18.dp)
                                Text(reply.kind.displayName, style = type.body.copy(color = colors.muted))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 八种心情：四列格子，图标在上、字在下；选中那格是深色底。 */
@Composable
private fun MoodGrid(selected: MoodLabel?, onSelect: (MoodLabel) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        MoodLabel.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                row.forEach { label ->
                    val on = label == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 62.dp)
                            .then(if (on) Modifier else Modifier.lift(colors, shape))
                            .clip(shape)
                            .background(if (on) colors.ink else colors.card)
                            .selectable(selected = on, role = Role.RadioButton) { onSelect(label) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        val fg = if (on) colors.background else colors.ink
                        Icon(label.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                        Text(label.displayName, style = type.caption.copy(fontSize = 14.tsp, fontWeight = FontWeight.W500, color = fg))
                    }
                }
            }
        }
    }
}

/** 强度 1–10：十个点，选到的和它左边的是实心玫瑰色，选中那个大一圈、带光晕。 */
@Composable
private fun IntensityPicker(value: Int, onChange: (Int) -> Unit) {
    val colors = QichiTheme.colors
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        for (i in 1..10) {
            val on = i == value
            Box(
                Modifier
                    .weight(1f)
                    .height(Sizes.touchTarget)
                    .selectable(selected = on, role = Role.RadioButton) { onChange(i) }
                    .semantics { contentDescription = "强度 $i" },
                contentAlignment = Alignment.Center,
            ) {
                if (on) Box(Modifier.size(26.dp).background(colors.personA.copy(alpha = .16f), QichiShapes.pill))
                Box(
                    Modifier
                        .size(if (on) 16.dp else 10.dp)
                        .background(if (i <= value) colors.personA else colors.personA.copy(alpha = .18f), QichiShapes.pill),
                )
            }
        }
    }
}

@Composable
private fun MoodTime(mood: Mood, zone: ZoneId) {
    Text(mood.createdAt.atZone(zone).format(HM), style = QichiTheme.typography.numeral.copy(fontSize = 12.tsp, color = QichiTheme.colors.muted))
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** 一张心情卡：谁的颜色淡淡衬底；人物标记、心情词、强度、右边心情图标；需要安慰时角上一张贴纸。 */
@Composable
private fun MoodCard(mood: Mood, people: People, pending: Boolean = false, below: @Composable () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val tint = people.person(mood.authorId).color()
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .lift(colors)
                .clip(QichiShapes.card)
                .background(colors.card)
                .background(tint.copy(alpha = .1f))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonMark(people.markChar(mood.authorId), people.person(mood.authorId), size = 24.dp)
                Text(feelingWord(mood.label, mood.intensity), style = type.headline.copy(fontSize = 22.tsp, lineHeight = 31.tsp, color = colors.ink))
                Text(mood.intensity.toString(), style = type.numeral.copy(fontSize = 15.tsp, color = colors.muted))
                if (pending) Icon(QichiIcons.Clock, contentDescription = "待发送", tint = colors.muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.weight(1f))
                Icon(mood.label.icon, contentDescription = mood.label.displayName, tint = tint, modifier = Modifier.size(24.dp))
            }
            mood.note?.let { Text(it, style = type.body.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.xs)) }
            below()
        }
        if (mood.needsComfort) Sticker("需要安慰", Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-12).dp), rotation = 5f)
    }
}
