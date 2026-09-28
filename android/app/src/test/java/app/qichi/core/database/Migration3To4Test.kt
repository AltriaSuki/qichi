package app.qichi.core.database

import android.content.Context
import androidx.room.Room
import app.cash.turbine.test
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.MessageKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本机库 3 → 4（P17-03、P17-04）：用导出的版本 3 结构建一个旧库、放进旧数据，再用现在的 App 打开。
 * Room 打开时会核对迁移后的结构和现在的定义完全一致，不一致直接报错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class Migration3To4Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"
    private var opened: QichiDatabase? = null

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(name)
    }

    /** 按 schemas/…/3.json 建出版本 3 的库 */
    private fun createVersion3(fill: (SupportSQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(File("schemas/app.qichi.core.database.QichiDatabase/3.json").readText()).jsonObject["database"]!!.jsonObject
        val statements = buildList {
            for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                add(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                (entity["indices"] as? kotlinx.serialization.json.JsonArray)?.forEach { index ->
                    add((index as JsonObject)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { add(it.jsonPrimitive.content) }
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    statements.forEach(db::execSQL)
                    fill(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun insertOld(db: SupportSQLiteDatabase, row: EntityRow) {
        db.execSQL(
            "INSERT INTO entities (type, id, roomId, seq, syncState, deleted, ownerId, parentId, sortSeq, sortTime, localTime, json, serverJson) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(row.type, row.id, row.roomId, row.seq, row.syncState.name, if (row.deleted) 1 else 0, row.ownerId, row.parentId, row.sortSeq, row.sortTime, row.localTime, row.json, row.serverJson),
        )
    }

    private fun message(kind: MessageKind, seq: Long, file: FileMeta? = null): Message =
        SyncFixtures.pendingMessage("第 $seq 条").copy(seq = seq, createdSeq = seq, kind = kind, file = file)

    @Test
    fun `消息和未读位置搬进聊天的表，其余留在原表；图片消息标上 image，查询结果和以前一样`() = runTest {
        val text = message(MessageKind.Text, 5)
        val photo = message(
            MessageKind.Image, 6,
            FileMeta(UUID.randomUUID(), roomId, FileKind.Image, "a.jpg", "image/jpeg", 1000, "sha", 640, 480, SyncFixtures.me, SyncFixtures.t0),
        )
        val marker = ReadMarker(UUID.randomUUID(), roomId, 7, SyncFixtures.t0, SyncFixtures.t0, null, null, SyncFixtures.me, 5)
        val todo = SyncFixtures.todo("买菜", seq = 8)
        createVersion3 { db ->
            listOf(text, photo, marker, todo).forEach { entity ->
                // 版本 3 的行没有 tag（toRow 现在会给图片消息填上，旧库里是没有的）
                insertOld(db, LocalStore.toRow(entity, SyncState.SYNCED, LocalStore.encode(LocalStore.typeOf(entity), entity), 1L).copy(tag = null))
            }
        }

        val db = Room.databaseBuilder(context, QichiDatabase::class.java, name)
            .addMigrations(QichiDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
            .also { opened = it }
        val entities = db.entities()

        // 消息、未读位置在聊天的表里，待办在原表里
        assertEquals(setOf(text.id.toString(), photo.id.toString()), db.chatEntities().listByType(roomId.toString(), "message").map { it.id }.toSet())
        assertEquals(1, db.chatEntities().readMarkers(roomId.toString(), SyncFixtures.me.toString()).size)
        assertTrue(db.generalEntities().listByType(roomId.toString(), "message").isEmpty())
        assertEquals("买菜", LocalStore.toLocal<app.qichi.shared.api.Todo>(entities.get("todo", todo.id.toString())!!).value.title)

        // 经统一入口读，结果和以前一样
        assertEquals(text, LocalStore.toLocal<Message>(entities.get("message", text.id.toString())!!).value)
        assertEquals(6L, entities.observeNewestMessageSeq(roomId.toString()).first())
        assertEquals(listOf(photo.id.toString()), entities.observeImageMessages(roomId.toString()).first().map { it.id })
        assertNull(entities.get("message", text.id.toString())!!.tag)
    }

    @Test
    fun `新写入的图片消息带 tag，按类型分到对应的表`() = runTest {
        val db = SyncFixtures.database().also { opened = it }
        val store = LocalStore(db)
        val photo = message(
            MessageKind.Image, 3,
            FileMeta(UUID.randomUUID(), roomId, FileKind.Image, "b.jpg", "image/jpeg", 1000, "sha", 640, 480, SyncFixtures.me, SyncFixtures.t0),
        )
        store.applyServer(photo)
        store.applyServer(SyncFixtures.todo("洗衣服", seq = 4))
        assertEquals(LocalStore.TAG_IMAGE, db.chatEntities().get("message", photo.id.toString())!!.tag)
        assertNull(db.generalEntities().get("message", photo.id.toString()))
        assertEquals(1, db.generalEntities().listByType(roomId.toString(), "todo").size)
        assertEquals(listOf(photo.id.toString()), db.entities().observeImageMessages(roomId.toString()).first().map { it.id })
    }

    @Test
    fun `写待办不会让聊天的查询重跑，写消息才会`() = runTest {
        val db = SyncFixtures.database().also { opened = it }
        val store = LocalStore(db)
        db.entities().observeNewestMessageSeq(roomId.toString()).test {
            assertNull(awaitItem())
            store.applyServer(SyncFixtures.todo("买菜", seq = 1))
            store.applyServer(message(MessageKind.Text, 9))
            // 分表以前，写待办这一下就会先来一次（内容不变的）结果
            assertEquals(9L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
