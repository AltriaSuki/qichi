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
import java.util.UUID

/**
 * 发件箱处理（docs/05-sync-offline.md §3.3）：按 localId 顺序一次只发一条。
 *
 * | 结果 | 处理 |
 * |---|---|
 * | 2xx | 用响应覆盖本地（SYNCED），删除这条，继续 |
 * | 网络错误、超时、5xx、429 | attempts + 1，停止本轮，交给 WorkManager 退避重试 |
 * | 409 conflict_version | 实体标记 CONFLICT，保留本地内容，删除这条，继续（文稿版本只删这条，草稿留着） |
 * | 其它 4xx | 实体标记 FAILED；同一实体后面排队的操作一并失败；不阻塞其它实体 |
 */
class OutboxProcessor(
    private val api: ApiClient,
    private val db: QichiDatabase,
    private val store: LocalStore,
) {
    sealed interface Result {
        /** 队列发完了；[rooms] 是这一轮有成功写入的房间（之后应拉取一次） */
        data class Done(val rooms: Set<UUID>) : Result

        /** 暂时发不出去（离线、服务器出错），稍后重试 */
        data class Retry(val rooms: Set<UUID>) : Result

        /** 登录已失效，停止 */
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
                    db.outbox().recordAttempt(row.localId, outcome.reason)
                    return Result.Retry(touched)
                }
                Outcome.Stop -> return Result.Stop
            }
        }
    }

    private sealed interface Outcome {
        data object Sent : Outcome
        data object Skip : Outcome
        data class TryLater(val reason: String) : Outcome
        data object Stop : Outcome
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
            return Outcome.TryLater(e.message ?: "网络错误")
        } catch (e: SessionExpiredException) {
            return Outcome.Stop
        } catch (e: ApiException) {
            return when {
                e.isRetryable -> Outcome.TryLater("${e.status} ${e.code}")
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
                    store.markFailed(row.entityType, row.entityId, e.userMessage)
                    Outcome.Skip
                }
            }
        }

        val body = response.bodyAsText()
        db.transaction {
            db.outbox().delete(row.localId)
            handleResponse(row, body)
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
            }
        }
    }
}
