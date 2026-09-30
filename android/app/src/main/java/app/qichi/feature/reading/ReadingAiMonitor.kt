package app.qichi.feature.reading

import app.qichi.shared.model.AiJobStatus
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 实时事件可能丢失；持续核对任务直到失败、结果同步回来或用户不再等待。 */
internal suspend fun monitorReadingAi(
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
