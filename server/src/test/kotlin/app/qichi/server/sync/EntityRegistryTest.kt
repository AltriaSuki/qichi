package app.qichi.server.sync

import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.model.EntityType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.CompositeDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 实体登记表（P13-13）：新加一种同步实体时漏登记、登记的快照字段和 App 解快照用的 Bootstrap 对不上，这里直接失败，
 * 不用等到手机上「这种东西一直同步不下来」才发现。
 */
@OptIn(ExperimentalSerializationApi::class)
class EntityRegistryTest {

    /** 在快照里有专门字段、不按列表给的 */
    private val special = setOf(EntityType.Room, EntityType.Member, EntityType.Message, EntityType.ReadMarker)
    private val specialFields = setOf("room", "members", "lastSeq", "readMarker", "messages", "hasMoreMessages")

    @Test
    fun `每种同步实体都登记了读法`() {
        assertEquals(EntityType.entries.toSet(), EntityRegistry.types)
        EntityType.entries.forEach { EntityRegistry.entry(it) }
    }

    @Test
    fun `除了房间、成员、消息、已读位置，每种实体都进快照`() {
        val notInSnapshot = EntityType.entries.filter { EntityRegistry.entry(it).snapshotField == null }.toSet()
        assertEquals(special, notInSnapshot)
    }

    @Test
    fun `登记的快照字段在 Bootstrap 里都有，元素类型和这种实体一致`() {
        val boot = Bootstrap.serializer().descriptor
        for (entry in EntityRegistry.snapshots) {
            val field = entry.snapshotField!!
            val index = boot.getElementIndex(field)
            assertNotEquals(CompositeDecoder.UNKNOWN_NAME, index, "${entry.type} 登记的快照字段 $field 在 Bootstrap 里没有")
            val list = boot.getElementDescriptor(index)
            assertEquals(StructureKind.LIST, list.kind, "$field 应该是列表")
            assertEquals(
                EntityCodec.serializer(entry.type).descriptor.serialName,
                list.getElementDescriptor(0).serialName,
                "$field 里放的应该是 ${entry.type}",
            )
        }
    }

    @Test
    fun `Bootstrap 里的每个实体列表都由登记表来填，没有一直空着的字段`() {
        val boot = Bootstrap.serializer().descriptor
        val listFields = boot.elementNames.toSet() - specialFields
        assertEquals(listFields, EntityRegistry.snapshots.map { it.snapshotField!! }.toSet())
        // 一个字段只放一种实体
        assertEquals(EntityRegistry.snapshots.size, listFields.size)
    }
}
