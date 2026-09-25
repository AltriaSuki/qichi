package app.qichi.server.tags

import app.qichi.server.db.ArchiveItems
import app.qichi.server.db.ArchiveRevisions
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Ideas
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.RenameTagRequest
import app.qichi.shared.api.RenameTagResult
import app.qichi.shared.model.EntityType
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.Tags
import app.qichi.shared.util.UuidV7
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * 标签改名 / 合并（P10-07）：标签不另存，改名就是把房间里所有灵感和档案正文里的 `#from` 改成 `#to`（规则在 shared 的 [Tags]）。
 * 两个人的都改；档案每改一条记一次修订（修订人是发起改名的人）。所有写入都经 [EntityWrites]，改动照常同步。
 */
class TagService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    suspend fun rename(userId: UUID, roomId: UUID, req: RenameTagRequest): RenameTagResult {
        val from = req.from.trim().removePrefix("#")
        val to = req.to.trim().removePrefix("#")
        validate {
            check(Tags.isValid(from), "from", "不是一个标签")
            check(Tags.isValid(to), "to", "标签最多三层，只能用文字、数字、下划线和连字符")
            check(from != to, "to", "新名字和原来一样")
            check(!to.startsWith("$from/"), "to", "不能改成自己下面的一层")
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            var ideas = 0
            Ideas.selectAll().where { (Ideas.roomId eq roomId) and Ideas.deletedAt.isNull() }.forEach { row ->
                val body = Tags.rename(row[Ideas.body], from, to)
                if (body != row[Ideas.body] && body.length in Limits.IDEA_BODY_LENGTH) {
                    writes.update(this, roomId, userId, EntityType.Idea, row[Ideas.id], Ideas) { it[Ideas.body] = body }
                    ideas++
                }
            }
            var items = 0
            ArchiveItems.selectAll().where { (ArchiveItems.roomId eq roomId) and ArchiveItems.deletedAt.isNull() }.forEach { row ->
                val title = Tags.rename(row[ArchiveItems.title], from, to)
                val body = Tags.rename(row[ArchiveItems.body], from, to)
                if (title == row[ArchiveItems.title] && body == row[ArchiveItems.body]) return@forEach
                // 改长了超出上限的（极少见）就不动它
                if (title.length !in Limits.ARCHIVE_TITLE_LENGTH || body.length > Limits.ARCHIVE_BODY_MAX) return@forEach
                val id = row[ArchiveItems.id]
                val next = row[ArchiveItems.currentRevision] + 1
                ArchiveRevisions.insert {
                    it[ArchiveRevisions.id] = UuidV7.generate()
                    it[itemId] = id
                    it[revision] = next
                    it[authorId] = userId
                    it[ArchiveRevisions.title] = title
                    it[ArchiveRevisions.body] = body
                    it[sourceMessageId] = row[ArchiveItems.sourceMessageId]
                    it[createdAt] = writes.now()
                }
                writes.update(this, roomId, userId, EntityType.ArchiveItem, id, ArchiveItems) {
                    it[ArchiveItems.title] = title
                    it[ArchiveItems.body] = body
                    it[ArchiveItems.currentRevision] = next
                    it[ArchiveItems.revisedBy] = userId
                }
                items++
            }
            RenameTagResult(ideas, items)
        }
    }
}
