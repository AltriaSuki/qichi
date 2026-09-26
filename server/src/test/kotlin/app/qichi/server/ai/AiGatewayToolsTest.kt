package app.qichi.server.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P11-01：网关带工具请求、识别工具调用、把工具结果交回去。 */
class AiGatewayToolsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val sse = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())

    private val listEvents = AiTool(
        "list_events", "按日期段列出日程",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from") { put("type", "string") }
                putJsonObject("to") { put("type", "string") }
            }
        },
    )

    /** 第二轮：用户问题 → 模型调了两个工具 → 两个结果。 */
    private val secondRound = AiRequest(
        "你是助手",
        listOf(
            AiMessage(AiMessage.Role.User, "下周六有什么安排？"),
            AiMessage(
                AiMessage.Role.Assistant, "",
                toolCalls = listOf(
                    AiToolCall("call_a", "list_events", """{"from":"2026-10-03","to":"2026-10-03"}"""),
                    AiToolCall("call_b", "list_events", """{"from":"2026-10-04","to":"2026-10-04"}"""),
                ),
            ),
            AiMessage(AiMessage.Role.Tool, "[1] 10月3日 09:00 去看外婆", toolCallId = "call_a"),
            AiMessage(AiMessage.Role.Tool, "（没有）", toolCallId = "call_b"),
        ),
        tools = listOf(listEvents),
    )

    private fun HttpRequestData.bodyJson() = Json.parseToJsonElement((body as TextContent).text).jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    // ── OpenAI 兼容 ──

    @Test
    fun `openai 兼容：带上工具定义和工具往返的历史，解析一次调两个工具`() = runBlocking {
        var seen: HttpRequestData? = null
        val engine = MockEngine { req ->
            seen = req
            respond(
                """{"model":"gpt-6-sol","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                   {"id":"call_1","type":"function","function":{"name":"list_events","arguments":"{\"from\":\"2026-10-03\",\"to\":\"2026-10-03\"}"}},
                   {"id":"call_2","type":"function","function":{"name":"search","arguments":"{\"q\":\"民宿\"}"}}
                   ]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":400,"completion_tokens":40}}""",
                HttpStatusCode.OK, json,
            )
        }
        val result = OpenAiCompatibleProvider("https://api.example.com/v1", "sk", "gpt-6-sol", HttpClient(engine)).complete(secondRound)
        assertEquals("", result.text)
        assertEquals(
            listOf(
                AiToolCall("call_1", "list_events", """{"from":"2026-10-03","to":"2026-10-03"}"""),
                AiToolCall("call_2", "search", """{"q":"民宿"}"""),
            ),
            result.toolCalls,
        )
        assertEquals(400, result.inputTokens)

        val body = seen!!.bodyJson()
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool.str("type"))
        assertEquals("list_events", tool["function"]!!.jsonObject.str("name"))
        assertEquals("object", tool["function"]!!.jsonObject["parameters"]!!.jsonObject.str("type"))

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it.str("role") })
        assertEquals(JsonNull, messages[2]["content"])
        val calls = messages[2]["tool_calls"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("call_a", "call_b"), calls.map { it.str("id") })
        assertEquals("""{"from":"2026-10-03","to":"2026-10-03"}""", calls[0]["function"]!!.jsonObject.str("arguments"))
        assertEquals("call_a", messages[3].str("tool_call_id"))
        assertEquals("[1] 10月3日 09:00 去看外婆", messages[3].str("content"))
    }

    @Test
    fun `openai 兼容：有工具也可以直接回答；没有工具时请求里不带 tools`() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val engine = MockEngine { req ->
            bodies += req.bodyJson()
            respond("""{"model":"m","choices":[{"message":{"content":"周六去看外婆。"}}]}""", HttpStatusCode.OK, json)
        }
        val provider = OpenAiCompatibleProvider("https://api.example.com/v1", "sk", "m", HttpClient(engine))
        val answered = provider.complete(AiRequest("s", listOf(AiMessage(AiMessage.Role.User, "q")), tools = listOf(listEvents)))
        assertEquals("周六去看外婆。", answered.text)
        assertTrue(answered.toolCalls.isEmpty())
        provider.complete(AiRequest("s", listOf(AiMessage(AiMessage.Role.User, "q"))))
        assertTrue("tools" in bodies[0])
        assertTrue("tools" !in bodies[1])
    }

    @Test
    fun `openai 兼容：流式里的工具调用分段到达，按序号拼起来`() = runBlocking {
        val engine = MockEngine {
            respond(
                """
                data: {"model":"gpt-6-sol","choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"我查一下。"}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"list_events","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"from\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"search","arguments":"{\"q\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"2026-10-03\"}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"\"民宿\"}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":300,"completion_tokens":25}}

                data: [DONE]

                """.trimIndent(),
                HttpStatusCode.OK, sse,
            )
        }
        val texts = mutableListOf<String>()
        val result = OpenAiCompatibleProvider("https://api.example.com/v1", "sk", "gpt-6-sol", HttpClient(engine))
            .stream(AiRequest("s", listOf(AiMessage(AiMessage.Role.User, "q")), tools = listOf(listEvents))) { texts += it }
        assertEquals(listOf("我查一下。"), texts)
        assertEquals(
            AiResult(
                "我查一下。", 300, 25, "gpt-6-sol",
                listOf(AiToolCall("call_1", "list_events", """{"from":"2026-10-03"}"""), AiToolCall("call_2", "search", """{"q":"民宿"}""")),
            ),
            result,
        )
    }

    // ── Anthropic ──

    @Test
    fun `anthropic：工具定义用 input_schema，调用是 tool_use 块，同一轮的结果合进一条用户消息`() = runBlocking {
        var seen: HttpRequestData? = null
        val engine = MockEngine { req ->
            seen = req
            respond(
                """{"model":"claude-x","content":[{"type":"text","text":"我查一下。"},
                   {"type":"tool_use","id":"toolu_1","name":"list_events","input":{"from":"2026-10-03","to":"2026-10-03"}}],
                   "stop_reason":"tool_use","usage":{"input_tokens":500,"output_tokens":30}}""",
                HttpStatusCode.OK, json,
            )
        }
        val result = AnthropicProvider("https://api.anthropic.com", "k", "claude-x", HttpClient(engine)).complete(secondRound)
        assertEquals("我查一下。", result.text)
        assertEquals(listOf(AiToolCall("toolu_1", "list_events", """{"from":"2026-10-03","to":"2026-10-03"}""")), result.toolCalls)

        val body = seen!!.bodyJson()
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("list_events", tool.str("name"))
        assertEquals("object", tool["input_schema"]!!.jsonObject.str("type"))

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.str("role") })
        val uses = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_use", "tool_use"), uses.map { it.str("type") })
        assertEquals("2026-10-03", uses[0]["input"]!!.jsonObject.str("from"))
        val results = messages[2]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("call_a", "call_b"), results.map { it.str("tool_use_id") })
        assertEquals(listOf("tool_result", "tool_result"), results.map { it.str("type") })
        assertEquals("（没有）", results[1].str("content"))
    }

    @Test
    fun `anthropic：流式里工具参数分段到达，文字照常一段段回调`() = runBlocking {
        val engine = MockEngine {
            respond(
                """
                event: message_start
                data: {"type":"message_start","message":{"model":"claude-x","usage":{"input_tokens":200}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"查一下。"}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"list_events","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"from\":"}}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_2","name":"search","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"2026-10-03\"}"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent(),
                HttpStatusCode.OK, sse,
            )
        }
        val texts = mutableListOf<String>()
        val result = AnthropicProvider("https://api.anthropic.com", "k", "claude-x", HttpClient(engine))
            .stream(AiRequest("s", listOf(AiMessage(AiMessage.Role.User, "q")), tools = listOf(listEvents))) { texts += it }
        assertEquals(listOf("查一下。"), texts)
        // 没有参数片段的工具调用给空对象
        assertEquals(
            AiResult(
                "查一下。", 200, 20, "claude-x",
                listOf(AiToolCall("toolu_1", "list_events", """{"from":"2026-10-03"}"""), AiToolCall("toolu_2", "search", "{}")),
            ),
            result,
        )
    }
}
