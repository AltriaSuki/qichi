package app.qichi.core.data

import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Plan
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 计划的本机读取（本阶段只读，创建和编辑在后续任务）。 */
class PlanRepository(private val db: QichiDatabase) {
    /** 房间里的计划（不含已删除）。 */
    fun observePlans(roomId: UUID): Flow<List<Local<Plan>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Plan.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Plan>(it) } }

    /** 房间里的里程碑（不含已删除）。 */
    fun observeMilestones(roomId: UUID): Flow<List<Local<Milestone>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Milestone.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Milestone>(it) } }
}
