package app.qichi.shared.model

import kotlinx.serialization.json.Json
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 契约测试：shared 里的枚举取值必须与 api/openapi.yaml 完全一致。 */
class EnumContractTest {

    private val schemas: Map<*, *> by lazy {
        val path = System.getProperty("qichi.openapi") ?: error("缺少系统属性 qichi.openapi")
        val doc = Load(LoadSettings.builder().build()).loadFromString(File(path).readText()) as Map<*, *>
        (doc["components"] as Map<*, *>)["schemas"] as Map<*, *>
    }

    private fun openApiEnum(name: String): List<String> {
        val schema = schemas[name] as? Map<*, *> ?: error("openapi.yaml 里没有 schema $name")
        return (schema["enum"] as List<*>).map { it.toString() }
    }

    private inline fun <reified E : Enum<E>> assertMatches(schemaName: String) {
        assertEquals(
            openApiEnum(schemaName),
            enumValues<E>().map { it.wireName },
            "$schemaName 与 openapi.yaml 不一致",
        )
    }

    @Test fun entityType() = assertMatches<EntityType>("EntityType")
    @Test fun changeOp() = assertMatches<ChangeOp>("ChangeOp")
    @Test fun memberRole() = assertMatches<MemberRole>("MemberRole")
    @Test fun moodLabel() = assertMatches<MoodLabel>("MoodLabel")
    @Test fun moodReplyKind() = assertMatches<MoodReplyKind>("MoodReplyKind")
    @Test fun messageKind() = assertMatches<MessageKind>("MessageKind")
    @Test fun fileKind() = assertMatches<FileKind>("FileKind")
    @Test fun trashType() = assertMatches<TrashType>("TrashType")
    @Test fun pushProvider() = assertMatches<PushProvider>("PushProvider")
    @Test fun problemCode() = assertMatches<ProblemCode>("ProblemCode")
    @Test fun aiJobKind() = assertMatches<AiJobKind>("AiJobKind")
    @Test fun aiJobStatus() = assertMatches<AiJobStatus>("AiJobStatus")
    @Test fun questionSource() = assertMatches<QuestionSource>("QuestionSource")
    @Test fun planStatus() = assertMatches<PlanStatus>("PlanStatus")
    @Test fun boardReactionKind() = assertMatches<BoardReactionKind>("BoardReactionKind")
    @Test fun archiveKind() = assertMatches<ArchiveKind>("ArchiveKind")
    @Test fun timelineEntryKind() = assertMatches<TimelineEntryKind>("TimelineEntryKind")

    @Test
    fun `JSON 序列化使用小写字符串`() {
        assertEquals("\"mood_response\"", Json.encodeToString(EntityType.MoodResponse))
        assertEquals(MoodLabel.Tired, Json.decodeFromString<MoodLabel>("\"tired\""))
    }

    @Test
    fun `wireName 与 fromWire 互逆`() {
        assertEquals("read_marker", EntityType.ReadMarker.wireName)
        assertEquals(PushProvider.UnifiedPush, fromWire<PushProvider>("unifiedpush"))
        assertNull(fromWireOrNull<MoodLabel>("sleepy"))
        assertFailsWith<IllegalArgumentException> { fromWire<MoodLabel>("sleepy") }
    }
}
