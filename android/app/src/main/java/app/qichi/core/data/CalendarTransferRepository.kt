package app.qichi.core.data

import android.content.Context
import android.net.Uri
import app.qichi.core.network.ApiClient
import app.qichi.core.network.post
import app.qichi.core.sync.SyncEngine
import app.qichi.shared.api.CalendarImportResult
import app.qichi.shared.api.CalendarSubscription
import app.qichi.shared.api.CalendarSubscriptionRequest
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** ICS 文件传输必须在线；导入后拉同步，日历仍只显示 Room 数据。 */
class CalendarTransferRepository(
    private val context: Context,
    private val api: ApiClient,
    private val sync: SyncEngine,
) {
    suspend fun importIcs(roomId: UUID, uri: Uri): CalendarImportResult {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) }
                ?: throw IOException("无法读取文件")
        }
        if (bytes.size > MAX_BYTES) throw IOException("ICS 文件不能超过 2 MiB")
        val form = MultiPartFormDataContent(formData {
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, "text/calendar")
                append(HttpHeaders.ContentDisposition, "filename=\"calendar.ics\"")
            })
        })
        val result = api.upload("rooms/$roomId/calendar/import", form, CalendarImportResult.serializer())
        sync.pull(roomId)
        return result
    }

    suspend fun export(roomId: UUID): ByteArray =
        api.execute(HttpMethod.Get, "rooms/$roomId/calendar/export.ics").bodyAsBytes()

    suspend fun save(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IOException("无法保存文件")
    }

    suspend fun subscription(roomId: UUID, reset: Boolean): CalendarSubscription =
        api.post("rooms/$roomId/calendar/subscription", CalendarSubscriptionRequest(reset))

    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
