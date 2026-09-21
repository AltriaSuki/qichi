package app.qichi.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * 本机数据库：离线优先的唯一数据源。界面只从这里读，从不直接显示网络结果。
 * 结构有变化时升 version 并写迁移（schemas/ 下有每个版本的结构导出）。
 */
@Database(
    entities = [EntityRow::class, SyncStateRow::class, OutboxRow::class, DraftRow::class],
    version = 1,
    exportSchema = true,
)
abstract class QichiDatabase : RoomDatabase() {
    abstract fun entities(): EntityDao
    abstract fun syncState(): SyncStateDao
    abstract fun outbox(): OutboxDao
    abstract fun drafts(): DraftDao

    suspend fun <R> transaction(block: suspend () -> R): R = withTransaction(block)

    companion object {
        fun create(context: Context): QichiDatabase =
            Room.databaseBuilder(context, QichiDatabase::class.java, "qichi.db").build()
    }
}
