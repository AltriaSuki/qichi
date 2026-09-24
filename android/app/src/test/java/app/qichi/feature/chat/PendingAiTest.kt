package app.qichi.feature.chat

import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.WsEvent
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P8-03：问 AI 边生成边显示。 */
class PendingAiTest {
    private val a = PendingAi(UUID.randomUUID(), "周六去哪？")
    private val b = PendingAi(UUID.randomUUID(), "预算多少？")

    @Test
    fun `到目前为止的回答放进对应的提问，后来的整段替换前面的`() {
        val once = listOf(a, b).withPartial(a.jobId, "周六去")
        assertEquals("周六去", once[0].partial)
        assertNull(once[1].partial)
        assertEquals("周六去北边那片海", once.withPartial(a.jobId, "周六去北边那片海")[0].partial)
    }

    @Test
    fun `已经失败的提问不被迟到的片段改回来`() {
        val failed = listOf(a.copy(failed = true))
        assertNull(failed.withPartial(a.jobId, "迟到的片段")[0].partial)
    }

    @Test
    fun `实时通道的 ai_delta 能解析`() {
        val room = UUID.randomUUID()
        val event = QichiJson.decodeFromString(WsEvent.serializer(), """{"type":"ai.delta","roomId":"$room","jobId":"${a.jobId}","text":"周六"}""")
        assertEquals(WsEvent.AiDelta(room, a.jobId, "周六"), event)
    }
}
