package app.qichi.core.data

import app.qichi.core.network.ApiClient
import app.qichi.shared.api.FileMeta
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.wireName
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentDisposition
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.File
import java.util.UUID

/** 文件的上传与下载（聊天附件、主视觉照片等都用它）。 */
class FileRepository(private val api: ApiClient) {

    /**
     * 上传文件（必须在线）。[fileId] 由调用方生成并在重试时沿用，服务端按它去重。
     * @param kind 默认按附件本身（图片 / 文件）；主视觉照片传 [FileKind.Hero]
     */
    suspend fun upload(
        roomId: UUID,
        fileId: UUID,
        attachment: PreparedAttachment,
        kind: FileKind = attachment.kind,
        onProgress: (Float) -> Unit = {},
    ): FileMeta {
        val form = MultiPartFormDataContent(
            formData {
                append("kind", kind.wireName)
                append("id", fileId.toString())
                append(
                    "file",
                    ChannelProvider(attachment.sizeBytes) { attachment.open().toByteReadChannel() },
                    Headers.build {
                        append(HttpHeaders.ContentType, attachment.mimeType)
                        append(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.File.withParameter(ContentDisposition.Parameters.FileName, attachment.fileName).toString(),
                        )
                    },
                )
            },
        )
        return api.upload("rooms/$roomId/files", form, FileMeta.serializer()) { sent, total ->
            val all = total ?: attachment.sizeBytes
            if (all > 0) onProgress((sent.toFloat() / all).coerceIn(0f, 1f))
        }
    }

    /** 下载文件到本机缓存（已经下载过就直接用），返回本地文件。 */
    suspend fun download(file: FileMeta, dir: File, onProgress: (Float) -> Unit): File {
        val target = File(File(dir, file.id.toString()).apply { mkdirs() }, safeFileName(file.fileName))
        if (target.exists() && target.length() == file.sizeBytes) return target
        api.download("files/${file.id}", target) { received, total ->
            val all = total ?: file.sizeBytes
            if (all > 0) onProgress((received.toFloat() / all).coerceIn(0f, 1f))
        }
        return target
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).ifBlank { "file" }
}
