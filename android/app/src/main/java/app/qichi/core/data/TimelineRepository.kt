package app.qichi.core.data

import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.sync.LocalStore
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.TimelinePage
import app.qichi.shared.api.TimelinePick
import app.qichi.shared.model.MessageKind
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import java.util.UUID

/**
 * 共同时间线：服务端按月拼装，需要联网（docs/04-api.md）。
 * 选照片从本机已有的聊天图片里挑，选中 / 取消直接发给服务端。
 */
class TimelineRepository(
    private val db: QichiDatabase,
    private val api: ApiClient,
) {
    /** [month] 为空时取最近一个有内容的月份。 */
    suspend fun month(roomId: UUID, month: YearMonth?): TimelinePage =
        api.get("rooms/$roomId/timeline" + (month?.let { "?year=${it.year}&month=${it.monthValue}" } ?: ""))

    suspend fun picks(roomId: UUID): List<TimelinePick> = api.get("rooms/$roomId/timeline/picks")

    suspend fun setPicked(roomId: UUID, fileId: UUID, picked: Boolean) {
        api.execute(if (picked) HttpMethod.Put else HttpMethod.Delete, "rooms/$roomId/timeline/picks/$fileId")
    }

    /** 房间里聊天发过的照片（没撤回、没删除），新的在前。 */
    fun observePhotos(roomId: UUID): Flow<List<FileMeta>> =
        db.entities().observeImageMessages(roomId.toString()).map { rows ->
            rows.map { LocalStore.toLocal<Message>(it).value }
                .filter { it.kind == MessageKind.Image && it.retractedAt == null && it.deletedAt == null }
                .mapNotNull { it.file }
                .distinctBy { it.id }
        }
}
