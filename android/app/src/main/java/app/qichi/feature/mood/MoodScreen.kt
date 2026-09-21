package app.qichi.feature.mood

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.MoodRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.CheckCircle
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ComfortFlag
import app.qichi.core.designsystem.component.IntensityTicks
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.displayName
import app.qichi.core.ui.feelingWord
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.MoodReplyKind
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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

/** 心情页，按 Mood.dc.html：此刻 / 深浅 / 几句 / 需要安慰 / 记下；下面是对方最近的心情与三种回应。 */
@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        BackBar(title = "心情", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.page, end = Spacing.page, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.detailSection),
        ) {
            Column {
                SectionLabel("此刻")
                FlowRow(maxItemsInEachRow = 4, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MoodLabel.entries.forEach { label ->
                        ChoicePill(label.displayName, selected = draft.label == label, onClick = { viewModel.onLabel(label) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            Column {
                SectionLabel("深浅")
                IntensityTicks(value = draft.intensity, onValueChange = viewModel::onIntensity)
            }
            Column {
                SectionLabel("几句")
                BasicTextField(
                    value = draft.note,
                    onValueChange = viewModel::onNote,
                    textStyle = type.body.copy(fontSize = 16.tsp, lineHeight = 30.tsp, color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 112.dp)
                        .clip(QichiShapes.card)
                        .background(colors.surface)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .semantics { contentDescription = "备注" },
                )
            }
            Row(
                Modifier
                    .heightIn(min = 46.dp)
                    .toggleable(value = draft.needsComfort, role = Role.Checkbox, onValueChange = viewModel::onComfort),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckCircle(checked = draft.needsComfort, onCheckedChange = null, modifier = Modifier.padding(end = 0.dp))
                Text("需要安慰", style = type.bodyLarge.copy(letterSpacing = 0.08.em, color = colors.ink))
            }
            PrimaryButton("记下", onClick = viewModel::save, enabled = draft.canSave, modifier = Modifier.fillMaxWidth())

            state.partnerMood?.let { mood ->
                Spacer(Modifier.height(14.dp))
                MoodBlock(
                    title = state.people.name(mood.value.authorId),
                    mood = mood.value,
                    people = state.people,
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoodReplyKind.entries.forEach { kind ->
                            val given = state.myRepliesToPartner.any { it.kind == kind }
                            ReplyPill(kind.displayName, given) { viewModel.toggleReply(kind) }
                        }
                    }
                }
            }

            state.myMood?.let { mood ->
                MoodBlock(title = "我", mood = mood.value, people = state.people, pending = mood.isPending) {
                    state.repliesToMe.forEach { reply ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PersonMark(state.people.markChar(reply.authorId), state.people.person(reply.authorId), size = 18.dp)
                            Text(reply.kind.displayName, style = type.body.copy(color = colors.muted))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodBlock(
    title: String,
    mood: Mood,
    people: People,
    pending: Boolean = false,
    below: @Composable () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(title)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PersonMark(people.markChar(mood.authorId), people.person(mood.authorId), size = 22.dp)
            Text(feelingWord(mood.label, mood.intensity), style = type.feeling.copy(color = colors.ink))
            Text(mood.intensity.toString(), style = type.numeral.copy(fontSize = 21.tsp, color = colors.muted))
            if (pending) {
                androidx.compose.material3.Icon(
                    QichiIcons.Clock, contentDescription = "待发送", tint = colors.muted,
                    modifier = Modifier.width(14.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (mood.needsComfort) ComfortFlag()
        }
        mood.note?.let { Text(it, style = type.body.copy(color = colors.muted)) }
        below()
    }
}

/** 已经给出的回应用 accent 字表示「已送达」，再点一下收回；不产生任何待处理的数字。 */
@Composable
private fun ReplyPill(text: String, given: Boolean, onClick: () -> Unit) = Pill(text, onClick = onClick, selected = given)
