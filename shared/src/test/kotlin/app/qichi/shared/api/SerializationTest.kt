package app.qichi.shared.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializationTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Sample(
        val id: Id,
        val at: Timestamp,
        val day: Day?,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val anniversary: Patch<Day?> = Patch.Absent,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val name: Patch<String> = Patch.Absent,
    )

    private val id = UUID.fromString("0192f000-aaaa-7bbb-8ccc-000000000001")
    private val at = Instant.parse("2026-09-21T11:30:00Z")

    @Test
    fun `id、时间、日期按 openapi 的格式输出`() {
        val json = QichiJson.encodeToString(Sample(id, at, LocalDate.of(2026, 9, 21)))
        assertEquals("""{"id":"0192f000-aaaa-7bbb-8ccc-000000000001","at":"2026-09-21T11:30:00Z","day":"2026-09-21"}""", json)
    }

    @Test
    fun `可为空的字段输出 null 而不是省略`() {
        val json = QichiJson.encodeToString(Sample(id, at, null))
        assertEquals("""{"id":"0192f000-aaaa-7bbb-8ccc-000000000001","at":"2026-09-21T11:30:00Z","day":null}""", json)
    }

    @Test
    fun `Patch 区分没发、发了 null、发了值`() {
        val base = """"id":"$id","at":"$at","day":null"""
        assertEquals(Patch.Absent, QichiJson.decodeFromString<Sample>("{$base}").anniversary)
        assertEquals(Patch.Value(null), QichiJson.decodeFromString<Sample>("""{$base,"anniversary":null}""").anniversary)
        assertEquals(
            Patch.Value(LocalDate.of(2020, 5, 20)),
            QichiJson.decodeFromString<Sample>("""{$base,"anniversary":"2020-05-20"}""").anniversary,
        )
    }

    @Test
    fun `Patch 输出时只包含发了的字段`() {
        val sample = Sample(id, at, null, anniversary = Patch.Value(null), name = Patch.Absent)
        val json = QichiJson.encodeToString(sample)
        assertEquals(
            """{"id":"0192f000-aaaa-7bbb-8ccc-000000000001","at":"2026-09-21T11:30:00Z","day":null,"anniversary":null}""",
            json,
        )
        assertEquals(sample, QichiJson.decodeFromString<Sample>(json))
    }

    @Test
    fun `未知字段被忽略`() {
        val decoded = QichiJson.decodeFromString<Sample>("""{"id":"$id","at":"$at","day":null,"future":1}""")
        assertEquals(id, decoded.id)
    }
}
