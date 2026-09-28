package app.qichi.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本机数据库：离线优先的唯一数据源。界面只从这里读，从不直接显示网络结果。
 * 结构有变化时升 version 并写迁移（schemas/ 下有每个版本的结构导出）。
 */
@Database(
    entities = [EntityRow::class, ChatEntityRow::class, SyncStateRow::class, OutboxRow::class, DraftRow::class, ChatHistoryRow::class, DocumentVersionRow::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        // 2：chat_history
        AutoMigration(from = 1, to = 2),
        // 3：document_versions（共同写作的版本缓存）
        AutoMigration(from = 2, to = 3),
        // 4：聊天单独一张表 chat_entities、tag 列（P17-03、P17-04），手写迁移 [MIGRATION_3_4]
    ],
)
abstract class QichiDatabase : RoomDatabase() {
    /** 不要直接用：经 [entities] 按类型分到两张表 */
    abstract fun generalEntities(): EntityDao

    /** 不要直接用：经 [entities] 按类型分到两张表 */
    abstract fun chatEntities(): ChatEntityDao

    private val entitiesRouter by lazy { Entities(generalEntities(), chatEntities()) }

    /** 所有同步实体的读写入口。 */
    fun entities(): Entities = entitiesRouter
    abstract fun syncState(): SyncStateDao
    abstract fun outbox(): OutboxDao
    abstract fun drafts(): DraftDao
    abstract fun chatHistory(): ChatHistoryDao
    abstract fun documentVersions(): DocumentVersionDao

    suspend fun <R> transaction(block: suspend () -> R): R = withTransaction(block)

    companion object {
        fun create(context: Context): QichiDatabase =
            Room.databaseBuilder(context, QichiDatabase::class.java, "qichi.db").addMigrations(MIGRATION_3_4).build()

        /**
         * 3 → 4（P17-03、P17-04）：消息和未读位置搬进 chat_entities；两张表都加 tag 列。
         * 以前存下的消息按原文粗筛出可能是图片的标上 image（和以前时间线的查法一样），调用方解开后再确认。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entities` ADD COLUMN `tag` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_entities` (`type` TEXT NOT NULL, `id` TEXT NOT NULL, `roomId` TEXT NOT NULL, " +
                        "`seq` INTEGER, `syncState` TEXT NOT NULL, `deleted` INTEGER NOT NULL, `ownerId` TEXT, `parentId` TEXT, " +
                        "`sortSeq` INTEGER, `sortTime` INTEGER, `localTime` INTEGER NOT NULL, `json` TEXT NOT NULL, `serverJson` TEXT, " +
                        "`tag` TEXT, PRIMARY KEY(`type`, `id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_entities_roomId_type_sortSeq` ON `chat_entities` (`roomId`, `type`, `sortSeq`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_entities_roomId_type_tag` ON `chat_entities` (`roomId`, `type`, `tag`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_entities_roomId_type_ownerId` ON `chat_entities` (`roomId`, `type`, `ownerId`)")
                val columns = "`type`, `id`, `roomId`, `seq`, `syncState`, `deleted`, `ownerId`, `parentId`, `sortSeq`, `sortTime`, `localTime`, `json`, `serverJson`"
                db.execSQL(
                    "INSERT INTO `chat_entities` ($columns, `tag`) " +
                        "SELECT $columns, CASE WHEN `type` = 'message' AND `json` LIKE '%\"kind\":\"image\"%' THEN 'image' END " +
                        "FROM `entities` WHERE `type` IN ('message', 'read_marker')",
                )
                db.execSQL("DELETE FROM `entities` WHERE `type` IN ('message', 'read_marker')")
            }
        }
    }
}
