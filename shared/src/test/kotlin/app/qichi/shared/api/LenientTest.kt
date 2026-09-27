package app.qichi.shared.api

import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MemberRole
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.MoodLabel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 旧版 App 读新版服务端的数据（P13-07）：认不出来的跳过并计数，认得的照常。 */
class LenientTest {
    private val t = Instant.parse("2026-09-26T08:00:00Z")
    private val roomId = UUID.fromString("0192f000-0000-7000-8000-000000000001")
    private val userId = UUID.fromString("0192f000-0000-7000-8000-000000000002")
    private fun id(n: Int) = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

    private fun mood(n: Int) = Mood(id(n), roomId, n.toLong(), t, t, null, null, userId, MoodLabel.Calm, 3, null, false)
    private fun message(n: Int) = Message(
        id(n), roomId, n.toLong(), t, t, null, null, userId, MessageKind.Text, "第 $n 条", null, null, null, null, null, null, n.toLong(),
    )
    private val room = Room(roomId, "我们", null, null, null, "Asia/Shanghai", userId, 1, t, t)
    private val member = Member(id(90), roomId, 2, t, t, null, null, userId, MemberRole.Owner, "a", "阿", null, t)

    private fun json(type: EntityType, value: Any): JsonObject = EntityCodec.encode(type, value).jsonObject
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun change(seq: Long, type: String, id: UUID, op: String, data: JsonElement?) = buildJsonObject {
        put("seq", seq)
        put("type", type)
        put("id", id.toString())
        put("op", op)
        put("data", data ?: JsonNull)
    }
    private fun page(vararg changes: JsonElement, toSeq: Long = 99, hasMore: Boolean = false) = buildJsonObject {
        put("fromSeq", 0)
        put("toSeq", toSeq)
        put("hasMore", hasMore)
        put("changes", JsonArray(changes.toList()))
    }

    @Test
    fun `同步一页：不认识的实体类型、枚举取值跳过，认得的照常，页信息不变`() {
        val sleepy = json(EntityType.Mood, mood(3)).with("label", JsonPrimitive("sleepy"))
        val decoded = Lenient.syncPage(
            page(
                change(1, "mood", id(1), "upsert", json(EntityType.Mood, mood(1))),
                change(2, "poll", id(2), "upsert", buildJsonObject { put("question", "去哪？") }),
                change(3, "mood", id(3), "upsert", sleepy),
                change(4, "mood", id(4), "delete", null),
                change(5, "poll", id(5), "delete", null),
                change(6, "mood", id(6), "archive", null),
                toSeq = 120, hasMore = true,
            ),
        )
        assertEquals(listOf(1L, 4L), decoded.value.changes.map { it.seq })
        assertEquals(mood(1), decoded.value.changes[0].entity)
        assertEquals(ChangeOp.Delete, decoded.value.changes[1].op)
        assertNull(decoded.value.changes[1].entity)
        assertEquals(4, decoded.skipped)
        assertEquals(0, decoded.partial)
        assertTrue(decoded.incomplete)
        assertEquals(120, decoded.value.toSeq)
        assertTrue(decoded.value.hasMore)
    }

    @Test
    fun `多了不认识的字段：照常用，但记为 partial`() {
        val withNewField = json(EntityType.Mood, mood(1)).with("weather", JsonPrimitive("rain"))
        val decoded = Lenient.syncPage(page(change(1, "mood", id(1), "upsert", withNewField)))
        assertEquals(mood(1), decoded.value.changes.single().entity)
        assertEquals(0, decoded.skipped)
        assertEquals(1, decoded.partial)
        assertTrue(decoded.incomplete)
    }

    @Test
    fun `全都认得时不算不完整`() {
        val decoded = Lenient.syncPage(page(change(1, "mood", id(1), "upsert", json(EntityType.Mood, mood(1)))))
        assertFalse(decoded.incomplete)
        // 变化外层多了字段不要紧：存的只是实体本身
        val envelope = change(2, "mood", id(2), "upsert", json(EntityType.Mood, mood(2))).with("actor", JsonPrimitive("x"))
        assertFalse(Lenient.syncPage(page(envelope)).incomplete)
    }

    @Test
    fun `页本身认不出来（缺 toSeq）是真出错了，照样抛出`() {
        val broken = JsonObject(page().filterKeys { it != "toSeq" })
        assertFailsWith<Exception> { Lenient.syncPage(broken) }
    }

    private fun bootstrap(extra: Map<String, JsonElement> = emptyMap(), moods: List<JsonElement> = emptyList(), readMarker: JsonElement = JsonNull): JsonObject {
        val base = QichiJson.encodeToJsonElement(
            Bootstrap.serializer(),
            Bootstrap(room, listOf(member), 50, null, emptyList(), emptyList(), emptyList(), emptyList(), listOf(message(10)), false),
        ).jsonObject
        return JsonObject(base + mapOf("moods" to JsonArray(moods), "readMarker" to readMarker) + extra)
    }

    @Test
    fun `快照：列表里认不出来的去掉，新版才有的整类数据也算跳过`() {
        val decoded = Lenient.container(
            Bootstrap.serializer(),
            bootstrap(
                moods = listOf(json(EntityType.Mood, mood(1)), json(EntityType.Mood, mood(2)).with("label", JsonPrimitive("sleepy"))),
                extra = mapOf("polls" to JsonArray(listOf(JsonObject(emptyMap()), JsonObject(emptyMap()))), "serverHint" to JsonPrimitive("x")),
            ),
        )
        assertEquals(listOf(mood(1)), decoded.value.moods)
        assertEquals(room, decoded.value.room)
        assertEquals(listOf(message(10)), decoded.value.messages)
        assertEquals(3, decoded.skipped)
        assertEquals(50, decoded.value.lastSeq)
    }

    @Test
    fun `快照：自己的未读位置认不出来时当作没有；房间认不出来整个失败`() {
        val marker = QichiJson.encodeToJsonElement(ReadMarker.serializer(), ReadMarker(id(7), roomId, 3, t, t, null, null, userId, 9)).jsonObject
        val ok = Lenient.container(Bootstrap.serializer(), bootstrap(readMarker = marker))
        assertEquals(9, ok.value.readMarker?.lastReadSeq)
        assertFalse(ok.incomplete)

        val bad = Lenient.container(Bootstrap.serializer(), bootstrap(readMarker = marker.with("lastReadSeq", JsonPrimitive("很多"))))
        assertNull(bad.value.readMarker)
        assertEquals(1, bad.skipped)

        val brokenRoom = bootstrap().with("room", JsonObject(emptyMap()))
        assertFailsWith<Exception> { Lenient.container(Bootstrap.serializer(), brokenRoom) }
    }

    @Test
    fun `一页历史消息：不认识的消息种类跳过`() {
        val poll = json(EntityType.Message, message(2)).with("kind", JsonPrimitive("poll"))
        val json = buildJsonObject {
            put("messages", JsonArray(listOf(json(EntityType.Message, message(3)), poll, json(EntityType.Message, message(1)))))
            put("hasMore", true)
        }
        val decoded = Lenient.container(MessagePage.serializer(), json)
        assertEquals(listOf(3L, 1L), decoded.value.messages.map { it.createdSeq })
        assertTrue(decoded.value.hasMore)
        assertEquals(1, decoded.skipped)
    }

    @Test
    fun `翻历史的位置按原始数据算：跳过的消息也算上，一页全被跳过也不当成翻到头`() {
        val poll = { n: Int -> json(EntityType.Message, message(n)).with("kind", JsonPrimitive("poll")) }
        val allUnknown = buildJsonObject {
            put("messages", JsonArray(listOf(poll(8), poll(7))))
            put("hasMore", true)
        }
        assertTrue(Lenient.container(MessagePage.serializer(), allUnknown).value.messages.isEmpty())
        assertEquals(7, Lenient.rawMin(allUnknown, "messages", "createdSeq"))
        assertNull(Lenient.rawMin(buildJsonObject { put("messages", JsonArray(emptyList())) }, "messages", "createdSeq"))
        assertNull(Lenient.rawMin(buildJsonObject { }, "messages", "createdSeq"))
    }

    @Test
    fun `服务端现在的数据都能严格解开（同版本不会误报「需要更新」）`() {
        for (type in EntityType.entries) {
            // 每种实体至少能被找到对应的数据类
            assertTrue(EntityCodec.serializer(type).descriptor.serialName.startsWith("app.qichi.shared.api."), type.name)
        }
        val decoded = Lenient.container(Bootstrap.serializer(), bootstrap(moods = listOf(json(EntityType.Mood, mood(1)))))
        assertFalse(decoded.incomplete)
    }

    @Test
    fun `客户端版本头：新格式读括号里的 versionCode，旧格式读 versionName 最后一段`() {
        assertEquals("android/0.2.345 (345)", androidClientHeader("0.2.345", 345))
        assertEquals(345, androidVersionCodeOf("android/0.2.345 (345)"))
        assertEquals(1, androidVersionCodeOf("android/0.2.dev (1)"))
        assertEquals(212, androidVersionCodeOf("android/0.2.212"))
        assertNull(androidVersionCodeOf("android/0.2.dev"))
        assertNull(androidVersionCodeOf("curl/8.0"))
        assertNull(androidVersionCodeOf(null))
    }
}
