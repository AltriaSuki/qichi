package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.SearchRepository
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.search.SearchCorpus
import app.qichi.core.search.SearchGroup
import app.qichi.core.search.SearchHit
import app.qichi.core.search.searchLocal
import app.qichi.core.ui.sourceKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UnifiedSearchState(
    val query: String = "",
    val groups: List<SearchGroup> = emptyList(),
    val searched: Boolean = false,
    /** 正在问服务器有没有更早的聊天 */
    val askingServer: Boolean = false,
)

/** 「我的」顶上的统一搜索（P16-06）：搜本机，离线也能用；在线时聊天再问一次服务器，补上本机没有的旧消息。 */
@HiltViewModel(assistedFactory = UnifiedSearchViewModel.Factory::class)
class UnifiedSearchViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val search: SearchRepository,
    private val network: NetworkMonitor,
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(UnifiedSearchState())
    val state: StateFlow<UnifiedSearchState> = _state.asStateFlow()
    private var job: Job? = null
    /** 读出来、解析好的本机内容和读的时间：打字时每改一个字都重读整个本机库太慢，短时间内接着用 */
    private var cached: Pair<Long, SearchCorpus>? = null

    private suspend fun loadCorpus(): SearchCorpus {
        val now = System.currentTimeMillis()
        cached?.let { (at, corpus) -> if (now - at < CORPUS_TTL_MS) return corpus }
        return withContext(Dispatchers.Default) { search.corpus(roomId) }.also { cached = now to it }
    }

    fun onQuery(query: String) {
        _state.update { it.copy(query = query) }
        job?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(groups = emptyList(), searched = false, askingServer = false) }
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val me = session.currentUserId
            val corpus = loadCorpus()
            val local = withContext(Dispatchers.Default) { searchLocal(corpus, query, me) }
            _state.update { it.copy(groups = local, searched = true, askingServer = network.isOnline.value) }
            if (!network.isOnline.value) return@launch
            val remote = try {
                search.searchChatOnServer(roomId, query.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // 本机没有的旧消息并进聊天那一组，再按同样的规则排一次
            val known = corpus.messages.map { it.id }.toSet()
            val extra = remote.filter { it.id !in known }
            val merged = if (extra.isEmpty()) local else withContext(Dispatchers.Default) {
                searchLocal(SearchCorpus(messages = corpus.messages + extra), query, me).firstOrNull()
                    ?.let { chat -> listOf(chat) + local.filter { it.type != "message" } }
                    ?.sortedBy { app.qichi.core.search.SEARCH_GROUP_ORDER.indexOf(it.type) }
                    ?: local
            }
            _state.update { it.copy(groups = merged, askingServer = false) }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): UnifiedSearchViewModel
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        /** 本机内容读一次用多久（这期间新同步来的内容，下次过期后再搜才出现） */
        const val CORPUS_TTL_MS = 20_000L
    }
}

/** 「我的 → 搜索」：一个搜索框，结果按类分组，点一条跳到原来那里。 */
@Composable
fun SearchScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (SearchHit) -> Unit,
    viewModel: UnifiedSearchViewModel = hiltViewModel<UnifiedSearchViewModel, UnifiedSearchViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        ItemTopBar("搜索", onBack, feature = Feature.Me)
        Box(
            Modifier
                .padding(horizontal = Spacing.page)
                .fillMaxWidth()
                .heightIn(min = Sizes.touchTarget)
                .clip(QichiShapes.pill)
                .background(colors.surface)
                .padding(horizontal = Spacing.m),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                singleLine = true,
                textStyle = type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = "搜索全部内容" },
                decorationBox = { inner ->
                    if (state.query.isEmpty()) Text("聊天、待办、日程、计划、灵感……", style = type.body.copy(color = colors.faint))
                    inner()
                },
            )
        }
        val hint = when {
            state.query.isBlank() -> "搜手机里已经同步下来的内容，没网也能搜。"
            state.searched && state.groups.isEmpty() && !state.askingServer -> "没有找到"
            state.askingServer -> "正在找更早的聊天…"
            else -> null
        }
        if (hint != null) {
            Text(hint, style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.s))
        }
        LazyColumn(contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, bottom = Spacing.xxl)) {
            state.groups.forEach { group ->
                item(key = "h-${group.type}") {
                    SectionLabel(
                        "${sourceKind(group.type)} · ${group.hits.size}",
                        modifier = Modifier.padding(top = Spacing.m).semantics { heading() },
                    )
                }
                items(group.hits, key = { "${group.type}-${it.source.id}-${it.focusId}-${it.snippet.hashCode()}" }) { hit ->
                    HitRow(hit, state.query, onClick = { onOpen(hit) })
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, query: String, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRow)
            .clickable(role = Role.Button, onClickLabel = "打开", onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                highlightTerms(hit.snippet, query, colors.accent),
                style = type.body.copy(color = colors.ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 摘录和标题不一样时（计划的进展、留言、摘录、问答的回答）写上它属于哪一条
            if (hit.source.label != hit.snippet && hit.source.type != "message") {
                Text(hit.source.label, style = type.caption.copy(fontSize = 12.tsp, color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(QichiIcons.ChevronRight, contentDescription = null, tint = colors.faint)
    }
}

/** 把每个搜索词在摘录里标成 accent 色（不分大小写）。 */
private fun highlightTerms(text: String, query: String, color: androidx.compose.ui.graphics.Color) =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        app.qichi.core.search.searchTerms(query).forEach { term ->
            var from = 0
            while (true) {
                val i = text.indexOf(term, from, ignoreCase = true)
                if (i < 0) break
                addStyle(androidx.compose.ui.text.SpanStyle(color = color), i, i + term.length)
                from = i + term.length
            }
        }
    }
