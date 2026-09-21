package app.qichi.server.files

import app.qichi.server.db.Files
import app.qichi.shared.api.FileMeta
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.fromWire
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toFileMeta() = FileMeta(
    id = this[Files.id],
    roomId = this[Files.roomId],
    kind = fromWire<FileKind>(this[Files.kind]),
    fileName = this[Files.fileName],
    mimeType = this[Files.mimeType],
    sizeBytes = this[Files.sizeBytes],
    sha256 = this[Files.sha256],
    width = this[Files.width],
    height = this[Files.height],
    uploadedBy = this[Files.uploadedBy],
    createdAt = this[Files.createdAt],
)
