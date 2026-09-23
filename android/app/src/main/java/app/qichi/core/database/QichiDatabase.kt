package app.qichi.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * 本机数据库：离线优先的唯一数据源。界面只从这里读，从不直接显示网络结果。
 * 结构有变化时升 version 并写迁移（schemas/ 下有每个版本的结构导出）。
 */
@Database(
    entities = [EntityRow::class, SyncStateRow::class, OutboxRow::class, DraftRow::class, ChatHistoryRow::class, DocumentVersionRow::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // 2：chat_history
        AutoMigration(from = 1, to = 2),
        // 3：document_versions（共同写作的版本缓存）
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class QichiDatabase : RoomDatabase() {
    abstract fun entities(): EntityDao
    abstract fun syncState(): SyncStateDao
    abstract fun outbox(): OutboxDao
    abstract fun drafts(): DraftDao
    abstract fun chatHistory(): ChatHistoryDao
    abstract fun documentVersions(): DocumentVersionDao

    suspend fun <R> transaction(block: suspend () -> R): R = withTransaction(block)

    companion object {
        fun create(context: Context): QichiDatabase =
            Room.databaseBuilder(context, QichiDatabase::class.java, "qichi.db").build()
    }
}
