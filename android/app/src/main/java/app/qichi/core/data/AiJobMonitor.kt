package app.qichi.core.data

import app.qichi.shared.model.AiJobStatus
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 等一个 AI 任务（阅读解释、总结、出题……）：实时通道的 ai.done 是一次性事件，断线期间的失败不会重播，
 * 所以每 5 秒主动核对一次，直到失败、结果同步回来（[isPending] 变成 false）或用户不再等待。
 * 完成了就调 [onDone]（一般是拉取一次，让结果同步下来）。
 */
suspend fun monitorAiJob(
    id: UUID,
    isPending: () -> Boolean,
    status: suspend (UUID) -> AiJobStatus,
    onDone: suspend () -> Unit,
    onFailed: () -> Unit,
) {
    while (isPending()) {
        try {
            when (status(id)) {
                AiJobStatus.Failed -> { onFailed(); return }
                AiJobStatus.Done -> onDone()
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 下载任务状态或同步结果暂时失败，下一轮继续核对。
        }
        delay(5_000)
    }
}
