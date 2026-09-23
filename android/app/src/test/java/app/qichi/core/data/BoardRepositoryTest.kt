package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.SyncState
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.Local
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.partner
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.feature.board.TopicSummary
import app.qichi.feature.board.sortTopics
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.CreateBoardPostRequest
import app.qichi.shared.api.CreateBoardTopicRequest
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.ReviseBoardPostRequest
import app.qichi.shared.model.BoardReactionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BoardRepositoryTest {

    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore
    private lateinit var board: BoardRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        store = LocalStore(db)
        val api = SyncFixtures.api(FakeServer().engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        board = BoardRepository(db, store, api, SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `断网写新主题：主题和第一条留言都先出现在本机，发件箱按顺序先建主题再发留言`() = runTest {
        val topic = board.createTopic(roomId, " 关于搬家 ", "我想了很久。")!!
        assertEquals("关于搬家", board.observeTopics(roomId).first().single().value.title)
        val post = board.observePosts(roomId).first().single()
        assertEquals(SyncState.PENDING, post.syncState)
        val ops = db.outbox().all()
        assertEquals(listOf("rooms/$roomId/board/topics", "rooms/$roomId/board/topics/${topic.id}/posts"), ops.map { it.path })
        assertEquals(topic.id, QichiJson.decodeFromString(CreateBoardTopicRequest.serializer(), ops[0].bodyJson!!).id)
        assertEquals(post.value.id, QichiJson.decodeFromString(CreateBoardPostRequest.serializer(), ops[1].bodyJson!!).id)
        assertNull(board.createTopic(roomId, "  ", "x"))
    }

    @Test
    fun `引用回复：本机先按同样的规则生成摘录；连续两次修订的基线依次递增`() = runTest {
        val topic = board.createTopic(roomId, "近况", "## 这周\n\n有点累，想早点睡。")!!
        val first = board.observePosts(roomId).first().single().value
        val reply = board.addPost(topic, "那今晚早点休息。", quote = first)!!
        assertEquals("这周 有点累，想早点睡。", reply.quoteExcerpt)
        assertEquals(me, reply.quoteAuthorId)

        board.revise(first, "有点累。")
        val once = board.observePosts(roomId).first().first { it.value.id == first.id }.value
        assertEquals(2, once.revision)
        board.revise(once, "有点累，但还好。")
        val bases = db.outbox().all().filter { it.method == "PATCH" }
            .map { QichiJson.decodeFromString(ReviseBoardPostRequest.serializer(), it.bodyJson!!).baseRevision }
        assertEquals(listOf(1, 2), bases)
    }

    @Test
    fun `回应和收回：收回后本机不再显示，发件箱是 PUT 然后 DELETE`() = runTest {
        val topic = board.createTopic(roomId, "近况", "这周有点累。")!!
        val post = board.observePosts(roomId).first().single().value.copy(authorId = partner)
        board.react(post, BoardReactionKind.Hug)
        val reaction = board.observeReactions(roomId).first().single().value
        assertEquals(BoardReactionKind.Hug, reaction.kind)
        board.unreact(reaction)
        assertTrue(board.observeReactions(roomId).first().isEmpty())
        val tail = db.outbox().all().takeLast(2)
        assertEquals(listOf("PUT", "DELETE"), tail.map { it.method })
        assertTrue(tail.all { it.path == "rooms/$roomId/board/posts/${post.id}/reactions/hug" })
        assertEquals(topic.id, post.topicId)
    }

    @Test
    fun `主题排序：置顶的在前，其余按最近留言`() {
        val t0 = Instant.parse("2026-09-01T00:00:00Z")
        fun topic(title: String, pinned: Instant? = null) =
            Local(BoardTopic(UUID.randomUUID(), roomId, 1, t0, t0, null, null, title, me, pinned), SyncState.SYNCED)
        fun post(at: Instant) = BoardPost(UUID.randomUUID(), roomId, 1, at, at, null, null, UUID.randomUUID(), me, "x", null, null, null, 1, null)
        val sorted = sortTopics(
            listOf(
                TopicSummary(topic("旧"), 1, post(t0.plusSeconds(10))),
                TopicSummary(topic("新"), 1, post(t0.plusSeconds(100))),
                TopicSummary(topic("置顶", pinned = t0), 0, null),
            ),
        )
        assertEquals(listOf("置顶", "新", "旧"), sorted.map { it.topic.value.title })
    }
}
