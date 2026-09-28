package app.qichi.server.ai

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ResilientGateway::class.java)

/**
 * 包在服务商实现外面的一层（AI 通路优化）：
 * - **很快就失败的可重试错误**（限流、502、连不上）当场隔一会儿再问，最多 [pauses] 那么多次。
 *   以前一出错就把整个任务交回队列，问 AI 查了几轮资料的也要从头来，还要等队列的退避。
 *   已经等了很久才失败的（多半是超时）不在这里重试，交给队列；流式已经出了字的也不重试，免得回答重来一遍。
 * - **长度用光了还一个字没写**（会「先想再答」的模型想完就用完了）：上限翻倍再问一次；还不行就算失败，不重试。
 * - 服务商没报用量（有的中转流式不给 usage）时按字数估一个，每月额度照样算得上。
 * - 每次调用记一行日志：用时、用量、有没有被截断，方便看慢在哪、花在哪。
 */
class ResilientGateway(
    private val inner: AiGateway,
    private val pauses: List<Long> = listOf(1_000L, 3_000L),
    /** 失败得比这快才当场重试 */
    private val fastFailMillis: Long = 10_000L,
) : AiGateway {
    override val model: String get() = inner.model

    override suspend fun complete(request: AiRequest): AiResult = call(request, spoke = { false }) { inner.complete(it) }

    override suspend fun stream(request: AiRequest, onText: suspend (String) -> Unit): AiResult {
        var spoke = false
        return call(request, spoke = { spoke }) { r ->
            inner.stream(r) { text ->
                spoke = true
                onText(text)
            }
        }
    }

    private suspend fun call(request: AiRequest, spoke: () -> Boolean, block: suspend (AiRequest) -> AiResult): AiResult {
        var req = request
        var retries = 0
        var grown = false
        // 放宽上限前那一次白用的量也要算上
        var wastedIn = 0
        var wastedOut = 0
        while (true) {
            val started = System.currentTimeMillis()
            val result = try {
                block(req)
            } catch (e: AiProviderException) {
                val elapsed = System.currentTimeMillis() - started
                if (!e.retryable || elapsed >= fastFailMillis || spoke() || retries >= pauses.size) throw e
                log.info("AI 调用 {} 毫秒后失败，{} 毫秒后再试：{}", elapsed, pauses[retries], e.message)
                delay(pauses[retries++])
                continue
            }
            val elapsed = System.currentTimeMillis() - started
            val filled = result.withUsage(req)
            if (filled.text.isBlank() && filled.toolCalls.isEmpty()) {
                // 只有截断才会走到这里（没截断的空回答服务商实现已经报错了）
                if (!grown && !spoke()) {
                    log.info("AI 写到长度上限 {} 还没出字，放宽到 {} 再问一次", req.maxTokens, req.maxTokens * 2)
                    wastedIn += filled.inputTokens
                    wastedOut += filled.outputTokens
                    grown = true
                    req = req.copy(maxTokens = req.maxTokens * 2)
                    continue
                }
                throw AiProviderException("写到长度上限 ${req.maxTokens} 还没有回答", retryable = false)
            }
            log.info(
                "AI 调用 {}：{} 毫秒，输入 {}，输出 {}{}{}",
                filled.model, elapsed, filled.inputTokens, filled.outputTokens,
                if (filled.toolCalls.isNotEmpty()) "，查 ${filled.toolCalls.size} 次" else "",
                if (filled.truncated) "，写到上限 ${req.maxTokens} 被截断" else "",
            )
            return filled.copy(inputTokens = filled.inputTokens + wastedIn, outputTokens = filled.outputTokens + wastedOut)
        }
    }

    companion object {
        /**
         * 服务商没报用量时按字数估：输入按两个字一个 token（和「停下」时的估法一样），输出按一个字一个 token。
         * 宁可估多一点，每月额度才管得住花费。
         */
        internal fun AiResult.withUsage(request: AiRequest): AiResult {
            if (inputTokens > 0 && outputTokens > 0) return this
            val inChars = request.system.length + request.messages.sumOf { m -> m.content.length + m.toolCalls.sumOf { it.arguments.length } } +
                request.tools.sumOf { it.description.length + it.parameters.toString().length }
            val outChars = text.length + toolCalls.sumOf { it.name.length + it.arguments.length }
            return copy(
                inputTokens = inputTokens.takeIf { it > 0 } ?: (inChars / 2),
                outputTokens = outputTokens.takeIf { it > 0 } ?: outChars,
            )
        }
    }
}
