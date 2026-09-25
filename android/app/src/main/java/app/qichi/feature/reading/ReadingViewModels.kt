package app.qichi.feature.reading

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.BookCache
import app.qichi.core.data.People
import app.qichi.core.data.ReadingRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.reading.EpubException
import app.qichi.core.reading.EpubOpener
import app.qichi.core.sync.Local
import app.qichi.core.sync.RealtimeClient
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Book
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.ReadingProgress
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.ReadExplainMode
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search

// ───────────────────────── 书架 ─────────────────────────

data class ShelfBook(
    val book: Local<Book>,
    val mine: ReadingProgress?,
    val partner: ReadingProgress?,
    /** 这台手机上已经有整本书（离线可读） */
    val cached: Boolean,
    /** 我能看到的书签、标注、摘录数 */
    val notes: Int,
)

data class ShelfState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val books: List<ShelfBook> = emptyList(),
    val cacheLimit: Long = 0,
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = ShelfViewModel.Factory::class)
class ShelfViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val reading: ReadingRepository,
    private val cache: BookCache,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }

    private val _adding = MutableStateFlow<Float?>(null)
    /** 正在加书：上传进度 0–1；为空表示没有在加 */
    val adding: StateFlow<Float?> = _adding
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<ShelfState> = combine(
        people, reading.observeBooks(roomId), reading.observeProgress(roomId),
        combine(reading.observeHighlights(roomId), cache.cached, cache.limitBytes) { h, c, l -> Triple(h, c, l) },
    ) { p, books, progress, (highlights, cached, limit) ->
        val zone = zoneOf(p.room?.timezone)
        ShelfState(
            people = p, zone = zone, today = todayIn(zone),
            books = books.map { b ->
                val id = b.value.id
                ShelfBook(
                    b,
                    progress.firstOrNull { it.bookId == id && it.userId == p.myUserId },
                    progress.firstOrNull { it.bookId == id && it.userId != p.myUserId },
                    b.value.fileId in cached,
                    highlights.count { it.value.bookId == id },
                )
            },
            cacheLimit = limit,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfState())

    fun cacheUsed(): Long = cache.usedBytes()

    fun add(uri: Uri) = viewModelScope.launch {
        _adding.value = 0f
        try {
            reading.addBook(roomId, uri) { _adding.value = it }
            _message.value = "放上书架了"
        } catch (e: CancellationException) {
            throw e
        } catch (e: EpubException) {
            _message.value = e.message
        } catch (_: Exception) {
            _message.value = "没能上传，加书需要联网"
        } finally {
            _adding.value = null
        }
    }

    fun updatePlan(book: Book, target: LocalDate?, note: String?) = viewModelScope.launch { reading.updatePlan(book, target, note) }
    fun delete(book: Book) = viewModelScope.launch { reading.delete(book) }
    fun removeDownload(book: Book) = viewModelScope.launch { cache.remove(book.fileId) }
    fun setCacheLimit(bytes: Long) = viewModelScope.launch { cache.setLimit(bytes) }
    fun messageShown() { _message.value = null }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): ShelfViewModel
    }
}

// ───────────────────────── 阅读器 ─────────────────────────

/** 目录里的一项（已经按层级展开）。 */
data class TocItem(val link: Link, val depth: Int)

data class ReaderState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val book: Book? = null,
    val loaded: Boolean = false,
    /** 整本书打开了（Readium 的 Publication 就绪） */
    val ready: Boolean = false,
    /** 下载进度 0–1；为空表示不在下载 */
    val downloading: Float? = null,
    val error: String? = null,
    val mine: ReadingProgress? = null,
    val partner: ReadingProgress? = null,
    /** 我的全部，加上对方共享的 */
    val highlights: List<Local<Highlight>> = emptyList(),
    val toc: List<TocItem> = emptyList(),
    /** 书一共几「页」（Readium 的位置数），页码写成「118 / 286」；还不知道时为 0 */
    val totalPositions: Int = 0,
    val online: Boolean = true,
    val aiEnabled: Boolean = false,
    /** 正在等 AI 回答的请求 */
    val aiPending: UUID? = null,
    val aiFailed: Boolean = false,
)

private data class AiAsk(val pending: UUID? = null, val failed: Boolean = false, val lastMode: ReadExplainMode? = null, val lastLocator: Locator? = null)

private data class Opened(
    val ready: Boolean = false,
    val downloading: Float? = null,
    val error: String? = null,
    val toc: List<TocItem> = emptyList(),
    val totalPositions: Int = 0,
)

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = ReaderViewModel.Factory::class)
class ReaderViewModel @AssistedInject constructor(
    @Assisted("roomId") private val roomId: UUID,
    @Assisted("bookId") private val bookId: UUID,
    private val reading: ReadingRepository,
    private val cache: BookCache,
    private val epubs: EpubOpener,
    rooms: RoomRepository,
    network: NetworkMonitor,
    realtime: RealtimeClient,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }
    private val opened = MutableStateFlow(Opened())
    private val ai = MutableStateFlow(AiAsk())
    private val environment = combine(network.isOnline, rooms.me, ai) { online, me, a -> Triple(online, me?.aiEnabled == true, a) }

    private val _openHighlight = MutableSharedFlow<UUID>(extraBufferCapacity = 1)
    /** AI 的回答同步回来了：页面打开这条标记 */
    val openHighlight: SharedFlow<UUID> = _openHighlight

    /** 打开的书；页面拿它创建 Readium 的阅读页 */
    var publication: Publication? = null
        private set

    /** 打开时要跳到的位置（我的进度） */
    var initialLocator: Locator? = null
        private set

    /** 阅读页当前的位置（旋转屏幕后从这里接着读） */
    private val current = MutableStateFlow<Locator?>(null)

    val state: StateFlow<ReaderState> = combine(
        combine(people, environment) { p, e -> p to e }, reading.observeBooks(roomId), reading.observeProgress(roomId), reading.observeHighlights(roomId), opened,
    ) { (p, env), books, progress, highlights, o ->
        val zone = zoneOf(p.room?.timezone)
        ReaderState(
            people = p, zone = zone, today = todayIn(zone),
            book = books.firstOrNull { it.value.id == bookId }?.value,
            loaded = true,
            ready = o.ready, downloading = o.downloading, error = o.error,
            mine = progress.firstOrNull { it.bookId == bookId && it.userId == p.myUserId },
            partner = progress.firstOrNull { it.bookId == bookId && it.userId != p.myUserId },
            highlights = highlights.filter { it.value.bookId == bookId }.sortedBy { it.value.createdAt },
            toc = o.toc,
            totalPositions = o.totalPositions,
            online = env.first,
            aiEnabled = env.second,
            aiPending = env.third.pending,
            aiFailed = env.third.failed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderState())

    init {
        viewModelScope.launch { load() }
        // 等的 AI 回答同步到本机：打开它
        viewModelScope.launch {
            state.collect { s ->
                val pending = s.aiPending ?: return@collect
                if (s.highlights.any { it.value.id == pending }) {
                    ai.update { it.copy(pending = null) }
                    _openHighlight.tryEmit(pending)
                }
            }
        }
        viewModelScope.launch {
            realtime.aiDone.collect { e ->
                if (e.jobId == ai.value.pending && e.status == AiJobStatus.Failed.wireName) ai.update { it.copy(pending = null, failed = true) }
            }
        }
        // 翻页停下 1.5 秒再保存进度（不在每次翻页都写）
        viewModelScope.launch {
            current.filterNotNull().debounce(1_500).collect { locator ->
                val book = state.value.book ?: return@collect
                reading.saveProgress(book, locator.toJSON().toString(), locator.locations.totalProgression ?: 0.0)
            }
        }
    }

    fun retry() = viewModelScope.launch { load() }

    private suspend fun load() {
        if (publication != null) return
        opened.update { it.copy(error = null) }
        val book = state.first { it.loaded }.book ?: return
        try {
            opened.update { it.copy(downloading = 0f) }
            val file = cache.open(book.fileId) { p -> opened.update { it.copy(downloading = p) } }
            val pub = epubs.open(file)
            initialLocator = current.value ?: state.value.mine?.locator?.let { parseLocator(it) }
            publication = pub
            opened.update { Opened(ready = true, toc = flatten(pub.tableOfContents, 0), totalPositions = runCatching<Int> { pub.positions().size }.getOrDefault(0)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EpubException) {
            opened.update { it.copy(downloading = null, error = e.message) }
        } catch (_: Exception) {
            opened.update { it.copy(downloading = null, error = "这本书还没下载到这台手机上，需要联网") }
        }
    }

    private fun flatten(links: List<Link>, depth: Int): List<TocItem> = links.flatMap { listOf(TocItem(it, depth)) + flatten(it.children, depth + 1) }

    fun parseLocator(json: String): Locator? = runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()

    /** 阅读页报告的位置（每次翻页）。 */
    fun onLocator(locator: Locator) {
        current.value = locator
        initialLocator = locator
    }

    fun addHighlight(kind: HighlightKind, locator: Locator, text: String, onAdded: (Highlight) -> Unit = {}) = viewModelScope.launch {
        val book = state.value.book ?: return@launch
        reading.addHighlight(book, kind, locator.toJSON().toString(), text.trim(), null, shared = false)?.let(onAdded)
    }

    /** 当前位置有书签就去掉，没有就加上。 */
    fun toggleBookmark(locator: Locator) = viewModelScope.launch {
        val book = state.value.book ?: return@launch
        val existing = bookmarkAt(locator)
        if (existing != null) reading.deleteHighlight(existing) else reading.addHighlight(book, HighlightKind.Bookmark, locator.toJSON().toString(), locator.title ?: "", null, false)
    }

    /** 同一章、进度相差不到 0.5% 的书签算作「这一页的书签」。 */
    fun bookmarkAt(locator: Locator?): Highlight? {
        locator ?: return null
        val me = state.value.people.myUserId
        return state.value.highlights.map { it.value }.firstOrNull { h ->
            h.kind == HighlightKind.Bookmark && h.userId == me && parseLocator(h.locator)?.let { l ->
                l.href == locator.href && kotlin.math.abs((l.locations.totalProgression ?: -1.0) - (locator.locations.totalProgression ?: -2.0)) < 0.005
            } == true
        }
    }

    /** 请 AI 解释或对比选中的段落（需要联网、AI 已开启）。返回不能请求的原因，能请求时为空。 */
    fun askAi(mode: ReadExplainMode, locator: Locator): String? {
        val s = state.value
        val book = s.book ?: return null
        when {
            !s.online -> return "需要联网"
            !s.aiEnabled -> return "AI 还没有开启"
            s.aiPending != null -> return "AI 还在看上一段"
        }
        val text = locator.text.highlight?.trim().orEmpty()
        if (text.isEmpty()) return "先选中一段文字"
        val jobId = UuidV7.generate()
        ai.value = AiAsk(pending = jobId, lastMode = mode, lastLocator = locator)
        viewModelScope.launch {
            try {
                reading.askAi(book, jobId, mode, locator.toJSON().toString(), text, locator.text.before.orEmpty(), locator.text.after.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ai.update { if (it.pending == jobId) it.copy(pending = null, failed = true) else it }
            }
        }
        return null
    }

    fun retryAi() {
        val a = ai.value
        val mode = a.lastMode ?: return
        val locator = a.lastLocator ?: return
        ai.update { it.copy(failed = false) }
        askAi(mode, locator)
    }

    fun dismissAi() = ai.update { AiAsk() }

    fun updateHighlight(h: Highlight, note: String?, shared: Boolean) = viewModelScope.launch { reading.updateHighlight(h, note, shared) }
    fun deleteHighlight(h: Highlight) = viewModelScope.launch { reading.deleteHighlight(h) }

    /** 书内搜索（最多 100 条）。 */
    suspend fun search(query: String): List<Locator> {
        val pub = publication ?: return emptyList()
        val iterator = pub.search(query.trim()) ?: return emptyList()
        val results = mutableListOf<Locator>()
        try {
            while (results.size < 100) {
                val page = iterator.next().getOrNull() ?: break
                if (page.locators.isEmpty()) break
                results += page.locators
            }
        } finally {
            iterator.close()
        }
        return results
    }

    override fun onCleared() {
        publication?.close()
        publication = null
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("roomId") roomId: UUID, @Assisted("bookId") bookId: UUID): ReaderViewModel
    }
}
