package app.qichi.core.sync

import app.qichi.core.database.OutboxRow
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.ApiException
import app.qichi.core.network.NetworkException
import app.qichi.core.network.SessionExpiredException
import app.qichi.shared.api.Change
import app.qichi.shared.api.CompleteTodoResponse
import app.qichi.shared.api.DocumentVersion
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.api.ReadingProgress
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.fromWire
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.util.UUID

/**
 * 发件箱处理（docs/05-sync-offline.md §3.3）：按 localId 顺序一次只发一条。
 *
 * | 结果 | 处理 |
 * |---|---|
 * | 2xx | 用响应覆盖本地（SYNCED），删除这条，继续 |
 * | 网络错误、超时、刷新令牌没刷成、401 | attempts + 1，停止本轮，交给 WorkManager 退避重试 |
 * | 5xx、429 | 同上；这一条服务器出错累计满 24 小时（断网不算）就当作失败，继续发后面的（P13-10，[OutboxError]） |
 * | 409 conflict_version | 实体标记 CONFLICT，保留本地内容，删除这条，继续（文稿版本只删这条，草稿留着） |
 * | 其它 4xx | 实体标记 FAILED；同一实体后面排队的操作一并失败；不阻塞其它实体 |
 * | 426 upgrade_required、登录已失效 | 停止，排队的都留着 |
 */
class OutboxProcessor(
    private val api: ApiClient,
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        /** 队列发完了；[rooms] 是这一轮有成功写入的房间（之后应拉取一次） */
        data class Done(val rooms: Set<UUID>) : Result

        /** 暂时发不出去（离线、服务器出错），稍后重试 */
        data class Retry(val rooms: Set<UUID>) : Result

        /** 登录已失效，或服务端要求先更新 App（426）：停止，排队的都留着 */
        data object Stop : Result
    }

    suspend fun drain(): Result {
        val touched = mutableSetOf<UUID>()
        while (true) {
            val row = db.outbox().nextPending() ?: return Result.Done(touched)
            when (val outcome = send(row)) {
                Outcome.Sent -> touched += UUID.fromString(row.roomId)
                Outcome.Skip -> Unit
                is Outcome.TryLater -> {
                    val previous = OutboxError.parse(row.lastError)
                    val error = if (outcome.serverError) previous.afterServerError(outcome.reason, now()) else previous.afterNetworkError(outcome.reason)
                    if (error.gaveUp) {
                        // 服务器一直收不下这一条：标失败，不再挡着后面的
                        fail(row, "服务器一直出错，没能发出去")
                    } else {
                        db.outbox().recordAttempt(row.localId, error.encode())
                        return Result.Retry(touched)
                    }
                }
                Outcome.Stop -> return Result.Stop
            }
        }
    }

    private sealed interface Outcome {
        data object Sent : Outcome
        data object Skip : Outcome

        /** 稍后再试；[serverError] 为 true 时计入 24 小时上限（断网、刷新令牌没刷成不计） */
        data class TryLater(val reason: String, val serverError: Boolean) : Outcome
        data object Stop : Outcome
    }

    /** 这一条发不出去了：实体标「发送失败」，同一实体后面排队的一并失败（文稿版本只删掉这条，草稿还在）。 */
    private suspend fun fail(row: OutboxRow, message: String) {
        if (row.kind == OutboxOp.KIND_DOC_VERSION) {
            db.outbox().delete(row.localId)
        } else {
            store.markFailed(row.entityType, row.entityId, message)
        }
    }

    private suspend fun send(row: OutboxRow): Outcome {
        val response = try {
            api.execute(
                method = HttpMethod.parse(row.method),
                path = row.path,
                body = row.bodyJson?.let { QichiJson.parseToJsonElement(it) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkException) {
            return Outcome.TryLater(e.message ?: "网络错误", serverError = false)
        } catch (e: SessionExpiredException) {
            return Outcome.Stop
        } catch (e: ApiException) {
            return when {
                e.isRetryable -> Outcome.TryLater("${e.status} ${e.code}", serverError = true)
                // 令牌还在（没失效）却 401：登录状态一时没刷成，稍后再试，不能当成这一条被拒（P13-10）
                e.status == 401 -> Outcome.TryLater("401 登录状态暂时没刷新成", serverError = false)
                // App 太旧：不算这一条失败，全部留着，装了新版再发（P13-07）
                e.code == ProblemCode.UpgradeRequired -> Outcome.Stop
                // 文稿版本：不动文稿本身的同步状态，草稿还在，界面看到基线落后就会进入重基线
                row.kind == OutboxOp.KIND_DOC_VERSION -> {
                    db.outbox().delete(row.localId)
                    Outcome.Skip
                }
                e.status == 409 && e.code == ProblemCode.ConflictVersion -> {
                    db.transaction {
                        db.outbox().delete(row.localId)
                        store.markConflict(row.entityType, row.entityId)
                    }
                    Outcome.Skip
                }
                else -> {
                    fail(row, e.userMessage)
                    Outcome.Skip
                }
            }
        }

        val body = response.bodyAsText()
        db.transaction {
            db.outbox().delete(row.localId)
            try {
                handleResponse(row, body)
            } catch (_: SerializationException) {
                // 服务端已经收下了，只是回应里有这个版本认不出来的东西（服务端比 App 新）：
                // 不能因此一直重发，拉取时再对齐（P13-07）
            }
        }
        return Outcome.Sent
    }

    /** 按操作种类处理成功的响应。 */
    private suspend fun handleResponse(row: OutboxRow, body: String) {
        when (row.kind) {
            OutboxOp.KIND_NO_CONTENT -> Unit
            OutboxOp.KIND_READ_MARKER -> store.applyReadMarker(QichiJson.decodeFromString(ReadMarker.serializer(), body))
            OutboxOp.KIND_CHANGE -> {
                val change = QichiJson.decodeFromString(Change.serializer(), body)
                change.data?.let { store.applyResponse(EntityCodec.decode(change.type, it) as SyncEntity) }
            }
            OutboxOp.KIND_READING_PROGRESS -> store.applyReadingProgress(QichiJson.decodeFromString(ReadingProgress.serializer(), body))
            OutboxOp.KIND_DOC_VERSION ->
                store.applyDocumentVersion(UUID.fromString(row.roomId), QichiJson.decodeFromString(DocumentVersion.serializer(), body))
            OutboxOp.KIND_FINDING_CONVERT -> {
                store.applyResponse(QichiJson.decodeFromString(app.qichi.shared.api.Annotation.serializer(), body))
                store.markSynced(row.entityType, row.entityId)
            }
            OutboxOp.KIND_TODO_COMPLETE -> {
                val result = QichiJson.decodeFromString(CompleteTodoResponse.serializer(), body)
                store.applyResponse(result.todo)
                result.next?.let { store.applyResponse(it) }
            }
            else -> {
                if (body.isBlank()) return
                val type = fromWire<EntityType>(row.entityType)
                val entity = EntityCodec.decode(type, QichiJson.parseToJsonElement(body)) as SyncEntity
                store.applyResponse(entity)
                // 服务端把这次创建并到了已有的一条上：本机先建的那行不留着（Q11）
                if (row.method == HttpMethod.Post.value) store.dropMerged(type, UUID.fromString(row.entityId), entity.id)
            }
        }
    }
}
