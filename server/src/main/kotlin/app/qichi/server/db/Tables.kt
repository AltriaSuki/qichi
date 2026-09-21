package app.qichi.server.db

import app.qichi.shared.api.QichiJson
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import java.time.Instant
import java.util.UUID

// Exposed 表定义，与 resources/db/migration/V1__init.sql 一一对应（结构以迁移文件为准，这里不建表）。

/** 需要同步的表共有的列：room_id、seq、时间戳、软删除。 */
abstract class SyncedTable(name: String) : Table(name) {
    val id: Column<UUID> = javaUUID("id")
    val roomId: Column<UUID> = javaUUID("room_id")
    val seq: Column<Long> = long("seq")
    val createdAt: Column<Instant> = timestamp("created_at")
    val updatedAt: Column<Instant> = timestamp("updated_at")
    val deletedAt: Column<Instant?> = timestamp("deleted_at").nullable()
    val deletedBy: Column<UUID?> = javaUUID("deleted_by").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Users : Table("users") {
    val id = javaUUID("id")
    val username = text("username")
    val passwordHash = text("password_hash")
    val displayName = text("display_name")
    val avatarFileId = javaUUID("avatar_file_id").nullable()
    val notificationPrefs = jsonb("notification_prefs", QichiJson, JsonObject.serializer())
    val passwordChangedAt = timestamp("password_changed_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokens : Table("refresh_tokens") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val familyId = javaUUID("family_id")
    val tokenHash = text("token_hash")
    val deviceName = text("device_name").nullable()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val replacedBy = javaUUID("replaced_by").nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Rooms : Table("rooms") {
    val id = javaUUID("id")
    val name = text("name")
    val avatarFileId = javaUUID("avatar_file_id").nullable()
    val heroFileId = javaUUID("hero_file_id").nullable()
    val anniversary = date("anniversary").nullable()
    val timezone = text("timezone")
    val createdBy = javaUUID("created_by")
    val lastSeq = long("last_seq")
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object RoomMembers : SyncedTable("room_members") {
    val userId = javaUUID("user_id")
    val role = text("role")
    val joinedAt = timestamp("joined_at")
}

object Invites : Table("invites") {
    val id = javaUUID("id")
    val roomId = javaUUID("room_id")
    val code = text("code")
    val createdBy = javaUUID("created_by")
    val expiresAt = timestamp("expires_at")
    val usedBy = javaUUID("used_by").nullable()
    val usedAt = timestamp("used_at").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ChangeLog : Table("change_log") {
    val roomId = javaUUID("room_id")
    val seq = long("seq")
    val entityType = text("entity_type")
    val entityId = javaUUID("entity_id")
    val op = text("op")
    val actorId = javaUUID("actor_id").nullable()
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(roomId, seq)
}

object Files : Table("files") {
    val id = javaUUID("id")
    val roomId = javaUUID("room_id")
    val uploadedBy = javaUUID("uploaded_by")
    val kind = text("kind")
    val fileName = text("file_name")
    val mimeType = text("mime_type")
    val sizeBytes = long("size_bytes")
    val sha256 = text("sha256")
    val storagePath = text("storage_path")
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Messages : SyncedTable("messages") {
    val authorId = javaUUID("author_id").nullable()
    val kind = text("kind")
    val body = text("body")
    val fileId = javaUUID("file_id").nullable()
    val replyToId = javaUUID("reply_to_id").nullable()
    val replyAuthorId = javaUUID("reply_author_id").nullable()
    val replyExcerpt = text("reply_excerpt").nullable()
    val retractedAt = timestamp("retracted_at").nullable()
    val retractedBy = javaUUID("retracted_by").nullable()
    val createdSeq = long("created_seq")
}

object ReadMarkers : SyncedTable("read_markers") {
    val userId = javaUUID("user_id")
    val lastReadSeq = long("last_read_seq")
}

object Moods : SyncedTable("moods") {
    val authorId = javaUUID("author_id")
    val label = text("label")
    val intensity = short("intensity")
    val note = text("note").nullable()
    val needsComfort = bool("needs_comfort")
}

object MoodResponses : SyncedTable("mood_responses") {
    val moodId = javaUUID("mood_id")
    val authorId = javaUUID("author_id")
    val kind = text("kind")
}

object Todos : SyncedTable("todos") {
    val title = text("title")
    val note = text("note").nullable()
    val createdBy = javaUUID("created_by")
    val assigneeId = javaUUID("assignee_id").nullable()
    val parentId = javaUUID("parent_id").nullable()
    val dueDate = date("due_date").nullable()
    val dueAt = timestamp("due_at").nullable()
    val recurrence = text("recurrence").nullable()
    val recurrencePrevId = javaUUID("recurrence_prev_id").nullable()
    val doneAt = timestamp("done_at").nullable()
    val doneBy = javaUUID("done_by").nullable()
}

object Events : SyncedTable("events") {
    val title = text("title")
    val note = text("note").nullable()
    val location = text("location").nullable()
    val allDay = bool("all_day")
    val startsAt = timestamp("starts_at").nullable()
    val endsAt = timestamp("ends_at").nullable()
    val startDate = date("start_date").nullable()
    val endDate = date("end_date").nullable()
    val participantIds = array<UUID>("participant_ids", UUIDColumnType())
    val createdBy = javaUUID("created_by")
    val icsUid = text("ics_uid").nullable()
}

object Devices : Table("devices") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val provider = text("provider")
    val token = text("token")
    val refreshFamilyId = javaUUID("refresh_family_id").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
