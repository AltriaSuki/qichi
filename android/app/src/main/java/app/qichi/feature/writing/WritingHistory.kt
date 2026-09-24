package app.qichi.feature.writing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.database.DocumentVersionRow
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.DiffView
import app.qichi.core.ui.MarkdownView
import app.qichi.core.ui.relativeDay
import app.qichi.core.ui.todayIn
import app.qichi.shared.util.Diff
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

private val hm = DateTimeFormatter.ofPattern("HH:mm")

/** 历史版本：列表（新的在前）→ 某个版本（和上一版逐行对比 / 全文）→ 另存为新版。 */
@Composable
internal fun HistoryView(state: EditorState, vm: DocumentEditorViewModel, onBack: () -> Unit, onRestored: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val versions by vm.versions.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { vm.refreshVersions() }

    open?.let { v ->
        VersionView(state, vm, versions.firstOrNull { it.version == v }, v, onBack = { open = null }, onRestored = onRestored)
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("历史版本", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            if (versions.isEmpty()) {
                Text(if (state.latestVersion == 0) "还没有保存过版本。" else "联网后能看到全部版本。",
                    style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
            }
            versions.forEach { row ->
                val author = UUID.fromString(row.authorId)
                val at = Instant.ofEpochMilli(row.createdAt).atZone(state.zone)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "查看 v${row.version}") { open = row.version },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text("v${row.version}", style = type.numeral.copy(fontSize = 22.tsp, color = colors.ink), modifier = Modifier.padding(end = 4.dp))
                    PersonMark(state.people.markChar(author), state.people.person(author), size = 20.dp)
                    Column(Modifier.weight(1f)) {
                        Text("${relativeDay(at.toLocalDate(), todayIn(state.zone)).first} ${at.format(hm)} · ${formatCount(row.charCount)} 字",
                            style = type.caption.copy(color = colors.muted))
                        row.restoredFromVersion?.let { Text("由 v$it 另存", style = type.caption.copy(color = colors.accent)) }
                    }
                    if (row.version == state.latestVersion) Text("最新", style = type.caption.copy(color = colors.muted))
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun VersionView(
    state: EditorState,
    vm: DocumentEditorViewModel,
    row: DocumentVersionRow?,
    version: Int,
    onBack: () -> Unit,
    onRestored: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    BackHandler(onBack = onBack)
    var bodies by remember(version) { mutableStateOf<Pair<String, String>?>(null) }
    var failed by remember(version) { mutableStateOf(false) }
    var showDiff by rememberSaveable { mutableStateOf(true) }
    var viewing by remember { mutableStateOf<java.util.UUID?>(null) }
    viewing?.let { id -> app.qichi.core.ui.ImageViewer(id, vm.urls, onDismiss = { viewing = null }) }
    var confirmRestore by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        val loaded = vm.bodies(version)
        bodies = loaded
        failed = loaded == null
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("v$version", onBack)
        Row(Modifier.padding(horizontal = Spacing.page), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill(if (version > 1) "和 v${version - 1} 对比" else "改动", showDiff, { showDiff = true })
            ChoicePill("全文", !showDiff, { showDiff = false })
        }
        row?.let { r ->
            val author = UUID.fromString(r.authorId)
            Text(
                "${state.people.name(author)}保存" + (r.restoredFromVersion?.let { "（由 v$it 另存）" } ?: ""),
                style = type.caption.copy(color = colors.muted),
                modifier = Modifier.padding(start = Spacing.page, end = Spacing.page, top = Spacing.s),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page, vertical = Spacing.m)) {
            val b = bodies
            when {
                b != null && showDiff -> DiffView(Diff.lines(b.first, b.second))
                b != null -> MarkdownView(
                    b.second, state.settings.fontSize.tsp, state.settings.lineHeight,
                    image = { fileId, alt -> DocumentImage(fileId, alt, vm.urls, onOpen = { viewing = fileId }) },
                )
                failed -> Text("需要联网才能打开这个版本。", style = type.body.copy(color = colors.muted))
                else -> Text("正在打开…", style = type.caption.copy(color = colors.faint))
            }
        }
        if (version < state.latestVersion && bodies != null) {
            PrimaryButton(
                "另存为 v${state.latestVersion + 1}",
                { if (state.unsaved) confirmRestore = true else { vm.restore(version, bodies!!.second); onRestored() } },
                enabled = !state.saving,
                modifier = Modifier.navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.m).fillMaxWidth(),
            )
        }
    }
    if (confirmRestore) {
        ConfirmDialog(
            "替换没保存的内容？",
            "你还有没保存的内容。把 v$version 另存为新版本，会用它替换掉这些内容。",
            "替换并另存",
            onConfirm = { confirmRestore = false; vm.restore(version, bodies!!.second); onRestored() },
            onDismiss = { confirmRestore = false },
        )
    }
}

/**
 * 重基线（docs/05-sync-offline.md §3.5）：对方存了更新的版本，而自己还有没保存的内容。
 * 展示最新版本与自己内容的逐行对比，由自己决定：保留自己的（基于最新版本继续写），或换成最新版本。
 */
@Composable
internal fun RebaseView(state: EditorState, onBack: () -> Unit, onKeepMine: () -> Unit, onTakeLatest: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    BackHandler(onBack = onBack)
    var confirmTake by remember { mutableStateOf(false) }
    val author = state.document?.value?.latestAuthorId
    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("重基线", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Text(
                "${state.people.name(author)}存了 v${state.latestVersion}，你的内容是在 v${state.baseVersion} 上写的。" +
                    "下面是 v${state.latestVersion} 和你的内容的逐行对比：划掉的是 v${state.latestVersion} 里有、你这里没有的；带底色的是你这里新写的。",
                style = type.body.copy(color = colors.muted),
            )
            Spacer(Modifier.height(Spacing.m))
            val latest = state.latestBody
            if (latest == null) {
                Text("需要联网才能取回 v${state.latestVersion}。", style = type.body.copy(color = colors.muted))
            } else {
                DiffView(Diff.lines(latest, state.text))
            }
            Spacer(Modifier.height(Spacing.l))
        }
        Column(Modifier.navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PrimaryButton("保留我的，基于 v${state.latestVersion} 继续", onKeepMine, modifier = Modifier.fillMaxWidth())
            TextAction("换成 v${state.latestVersion}，不要我的", { confirmTake = true }, color = colors.muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
    if (confirmTake) {
        ConfirmDialog("不要自己的内容？", "没保存的内容会丢掉，换成 v${state.latestVersion}。这一步不能撤回。", "换成最新版本",
            onConfirm = { confirmTake = false; onTakeLatest() }, onDismiss = { confirmTake = false })
    }
}
