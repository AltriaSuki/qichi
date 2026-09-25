package app.qichi.feature.ideas

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.ArchiveRepository
import app.qichi.core.data.IdeaRepository
import app.qichi.core.data.TagRepository
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.FeatureTile
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.ApiException
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.sync.SyncEngine
import app.qichi.shared.rules.Tags
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@HiltViewModel(assistedFactory = TagsViewModel.Factory::class)
class TagsViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    ideas: IdeaRepository,
    archive: ArchiveRepository,
    private val tags: TagRepository,
    private val network: NetworkMonitor,
    private val sync: SyncEngine,
) : ViewModel() {
    val tree: StateFlow<List<TagNode>> = combine(ideas.observeIdeas(roomId), archive.observeItems(roomId)) { i, a ->
        tagTree(
            i.map { it.value }.filter { it.deletedAt == null }.map { it.body },
            a.map { it.value }.filter { it.deletedAt == null }.map { it.title + "\n" + it.body },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val message: SharedFlow<String> = _message

    /** 改名 / 合并：服务端改两个人的正文，再拉一次同步。成功后 [onDone]。 */
    fun rename(from: String, to: String, onDone: () -> Unit) {
        val target = to.trim().removePrefix("#")
        when {
            !Tags.isValid(target) -> { _message.tryEmit("标签最多三层，只能用文字、数字、下划线和连字符"); return }
            target == from -> { onDone(); return }
            !network.isOnline.value -> { _message.tryEmit("离线时改不了标签"); return }
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = tags.rename(roomId, from, target)
                runCatching { sync.pull(roomId) }
                _message.emit("改好了：灵感 ${result.ideas} 条、档案 ${result.archiveItems} 条")
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _message.emit(e.userMessage)
            } catch (_: Exception) {
                _message.emit("没改成，再试一次")
            } finally {
                _busy.value = false
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TagsViewModel
    }
}

/**
 * 「标签」（按 New-Tags）：标签树，每行各有几条灵感、几条档案；子标签缩进、用虚线连着。
 * 点一个标签可以改名，改成已有的名字就是合并，两个人的灵感和档案都跟着变。
 */
@Composable
fun TagsScreen(
    roomId: UUID,
    onBack: () -> Unit,
    vm: TagsViewModel = hiltViewModel<TagsViewModel, TagsViewModel.Factory>(key = "tags-$roomId") { it.create(roomId) },
) {
    val tree by vm.tree.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<TagNode?>(null) }
    LaunchedEffect(vm) { vm.message.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        FeatureTopBar(Feature.Ideas, onBack, title = "标签", note = null, icon = QichiIcons.Tag)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.page, end = Spacing.page, top = 4.dp, bottom = Spacing.l)) {
            if (tree.isEmpty()) {
                Text("还没有标签。", style = QichiTheme.typography.caption.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.s))
            }
            var topIndex = -1
            tree.forEachIndexed { i, node ->
                if (node.depth == 0) topIndex++
                TagRow(node, first = i == 0, colorIndex = topIndex) { renaming = node }
            }
            Row(Modifier.padding(top = Spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HandNote("写的时候打 # 就是标签，", fontSizeSp = 18f, rotation = -2f)
                HandNote("打 / 能分层", fontSizeSp = 18f, color = colors.accent, rotation = -2f)
            }
        }
    }

    renaming?.let { node ->
        var name by remember(node.tag) { mutableStateOf(node.tag) }
        AlertDialog(
            onDismissRequest = { if (!busy) renaming = null },
            containerColor = colors.paper,
            title = { Text("#${node.tag}", style = QichiTheme.typography.numeral.copy(fontSize = 18.tsp, color = colors.ink)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    QichiTextField(name, { name = it.take(60) }, label = "改成")
                    Text("改成已有的名字就是合并。两个人的灵感和档案都会跟着改。", style = QichiTheme.typography.caption.copy(color = colors.muted))
                }
            },
            confirmButton = { TextAction(if (busy) "正在改…" else "改名", { vm.rename(node.tag, name) { renaming = null } }, enabled = !busy && name.isNotBlank()) },
            dismissButton = { TextAction("取消", { renaming = null }, enabled = !busy, color = colors.muted) },
        )
    }
}

@Composable
private fun TagRow(node: TagNode, first: Boolean, colorIndex: Int, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val tint = listOf(colors.personB, colors.accent, colors.personA, colors.muted)[Math.floorMod(colorIndex, 4)]
    val child = node.depth > 0
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (first || child) Modifier else Modifier.dashedDivider(colors, atTop = true))
            .clickable(role = Role.Button, onClickLabel = "改名", onClick = onClick)
            .heightIn(min = if (child) 50.dp else 58.dp)
            .padding(start = if (child) (14 * node.depth).dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (child) {
            // 虚线连接：一道竖线接一道横线；同一层的最后一个只画到一半
            val line = colors.line2
            Box(
                Modifier.width(22.dp).fillMaxHeight().heightIn(min = 50.dp).drawBehind {
                    val x = 8.dp.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                    drawLine(line, Offset(x, 0f), Offset(x, if (node.last) size.height / 2 else size.height), 1.3.dp.toPx(), pathEffect = dash)
                    drawLine(line, Offset(x, size.height / 2), Offset(x + 12.dp.toPx(), size.height / 2), 1.3.dp.toPx(), pathEffect = dash)
                },
            )
        }
        FeatureTile(QichiIcons.Tag, tint, size = if (child) 28.dp else 34.dp)
        Text(
            if (child) "/${node.name}" else "#${node.tag}",
            style = type.numeral.copy(fontSize = (if (child) 14 else 15).tsp, fontWeight = FontWeight.W500, color = colors.ink),
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("灵感", style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
            Text("${node.ideas}", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
            Text("档案", style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
            Text("${node.archiveItems}", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
        }
    }
}
