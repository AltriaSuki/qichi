package app.qichi.shared.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNull

class TodoCompatibilityTest {
    @Test fun `旧手机缓存的待办缺少 planId 仍可读取`() {
        val id = UUID.randomUUID()
        val roomId = UUID.randomUUID()
        val now = Instant.parse("2026-09-21T12:00:00Z")
        val todo = Todo(id, roomId, 1, now, now, null, null, "订车票", null, id,
            null, null, null, null, null, null, null, null)
        val oldJson = JsonObject(QichiJson.encodeToJsonElement(Todo.serializer(), todo).jsonObject - "planId")

        val restored = QichiJson.decodeFromJsonElement(Todo.serializer(), oldJson)
        assertNull(restored.planId)
    }
}
