package app.qichi.shared.api

import kotlinx.serialization.Serializable

/** 同步实体 idea：随手记下的想法。只有作者能修改；两个人都能删除（进回收站）。 */
@Serializable
data class Idea(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val authorId: Id,
    val body: String,
) : SyncEntity

@Serializable
data class CreateIdeaRequest(val id: Id, val body: String)

@Serializable
data class UpdateIdeaRequest(val body: String)

/** 标签改名或合并（P10-07）：`from` / `to` 都不带 #。 */
@Serializable
data class RenameTagRequest(val from: String, val to: String)

@Serializable
data class RenameTagResult(val ideas: Int, val archiveItems: Int)
