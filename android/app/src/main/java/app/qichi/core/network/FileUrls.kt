package app.qichi.core.network

import app.qichi.shared.api.API_PREFIX
import java.util.UUID

/** 文件与缩略图的完整地址（给图片加载库用；请求经 ApiClient.http 发出，会自动带令牌）。 */
class FileUrls(baseUrl: String) {
    private val root = baseUrl.trimEnd('/') + API_PREFIX

    fun original(fileId: UUID): String = "$root/files/$fileId"

    /** @param width 200、400 或 800 */
    fun thumbnail(fileId: UUID, width: Int = 800): String = "$root/files/$fileId/thumb?w=$width"

    /** 相对路径（给 ApiClient.download 用） */
    fun originalPath(fileId: UUID): String = "files/$fileId"
}
