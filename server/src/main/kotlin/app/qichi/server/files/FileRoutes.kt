package app.qichi.server.files

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.rules.Limits
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.file.Path

/** multipart 里除文件内容之外的部分（边界、kind、id、文件名）留的余量 */
private const val FORM_OVERHEAD = 64L * 1024

/** 文件内容不会变：缓存一年，按 sha256 作 ETag。 */
private const val IMMUTABLE_CACHE = "private, max-age=31536000, immutable"

fun Route.fileRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        post("/rooms/{roomId}/files") {
            val userId = call.user.userId
            val roomId = call.uuidParam("roomId")
            // 先确认身份与大小，再开始收文件内容
            ctx.rooms.requireMemberTx(roomId, userId)
            call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let {
                if (it > Limits.FILE_MAX_BYTES + FORM_OVERHEAD) payloadTooLarge(Limits.FILE_MAX_BYTES)
            }
            if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                throw ApiException(ProblemCode.UnsupportedMediaType, "需要 multipart/form-data")
            }

            var kind: String? = null
            var id: String? = null
            var fileName: String? = null
            var declaredType: String? = null
            var staged: StagedFile? = null
            try {
                call.receiveMultipart(formFieldLimit = Limits.FILE_MAX_BYTES + FORM_OVERHEAD).forEachPart { part ->
                    try {
                        when (part) {
                            is PartData.FormItem -> when (part.name) {
                                "kind" -> kind = part.value
                                "id" -> id = part.value
                            }
                            is PartData.FileItem -> if (part.name == "file" && staged == null) {
                                fileName = part.originalFileName
                                declaredType = part.contentType?.toString()
                                staged = ctx.files.stage(part.provider(), ctx.files.limitFor(kind))
                            }
                            else -> Unit
                        }
                    } finally {
                        part.dispose()
                    }
                }
            } catch (e: Throwable) {
                staged?.let(ctx.files::discard)
                throw e
            }
            call.respondCreated(ctx.files.create(userId, roomId, UploadForm(kind, id, fileName, declaredType, staged)))
        }

        get("/files/{fileId}") {
            val file = ctx.files.open(call.user.userId, call.uuidParam("fileId"))
            val meta = file.meta
            val disposition = if (meta.kind.isImage) ContentDisposition.Inline else ContentDisposition.Attachment
            call.response.header(
                HttpHeaders.ContentDisposition,
                disposition
                    .withParameter(ContentDisposition.Parameters.FileName, asciiFallback(meta.fileName))
                    .withParameter(ContentDisposition.Parameters.FileNameAsterisk, meta.fileName, encodeValue = true)
                    .toString(),
            )
            call.respondImmutable(file.path, ContentType.parse(meta.mimeType), etag = meta.sha256)
        }

        get("/files/{fileId}/thumb") {
            val width = call.request.queryParameters["w"]?.toIntOrNull() ?: 0
            val fileId = call.uuidParam("fileId")
            val thumb = ctx.files.thumbnail(call.user.userId, fileId, width)
            call.respondImmutable(thumb, ContentType.Image.JPEG, etag = "$fileId-w$width")
        }
    }
}

/** 不变的内容：带 ETag 与长缓存；If-None-Match 命中时 304。Range 由 PartialContent 插件处理。 */
private suspend fun ApplicationCall.respondImmutable(path: Path, type: ContentType, etag: String) {
    val tag = "\"$etag\""
    response.header(HttpHeaders.ETag, tag)
    response.header(HttpHeaders.CacheControl, IMMUTABLE_CACHE)
    if (request.header(HttpHeaders.IfNoneMatch)?.split(',')?.any { it.trim() == tag } == true) {
        respond(HttpStatusCode.NotModified)
        return
    }
    respond(LocalPathContent(path, type))
}

/** 给不认 filename* 的旧客户端：非 ASCII 字符换成下划线，保留扩展名。 */
private fun asciiFallback(name: String): String =
    name.map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }.joinToString("")
