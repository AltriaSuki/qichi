package app.qichi.core.data

import app.qichi.core.network.ApiClient
import app.qichi.core.network.post
import app.qichi.shared.api.RenameTagRequest
import app.qichi.shared.api.RenameTagResult
import java.util.UUID

/**
 * 标签（P10-07）：标签不另存，本机按灵感和档案的正文算；这里只有改名 / 合并，交给服务端批量改正文
 * （两个人的都改），改完的内容照常同步下来。需要联网，不进发件箱。
 */
class TagRepository(private val api: ApiClient) {
    suspend fun rename(roomId: UUID, from: String, to: String): RenameTagResult =
        api.post("rooms/$roomId/tags/rename", RenameTagRequest(from, to))
}
