package app.qichi.server.plans

import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Plans
import app.qichi.server.db.Rooms
import app.qichi.server.db.Tx
import app.qichi.shared.api.Todo
import app.qichi.shared.model.EntityType
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 下一步连着待办（P14-03）：待办改了名字、交给谁、截止，连着它的计划的下一步跟着变；做完或删掉，下一步结束；
 * 移出那个计划，只断开（下一步的文字留着）。都在待办那次写入的事务里经 [EntityWrites] 写变化，两台手机都收得到。
 * 回收站里的计划也算：恢复后不会带着过期的链接。
 */
internal object LinkedSteps {
    /** 这件待办当下一步时的样子：名字、交给谁（空 = 两个人）、截止那天（只有时刻的按房间时区算日期）。 */
    data class Step(val text: String, val owner: UUID?, val due: LocalDate?)

    fun stepOf(todo: Todo): Step {
        val due = todo.dueDate ?: todo.dueAt?.atZone(roomZone(todo.roomId))?.toLocalDate()
        return Step(todo.title.take(Limits.PLAN_STEP_LENGTH.last), todo.assigneeId, due)
    }

    /** 待办改过之后（[todo] 是改后的样子）：连着它的下一步跟着变；它已经不在那个计划里的，只断开。 */
    fun follow(tx: Tx, writes: EntityWrites, userId: UUID, todo: Todo) {
        val rows = linked(todo.roomId, todo.id)
        if (rows.isEmpty()) return
        val step = stepOf(todo)
        rows.forEach { row ->
            val planId = row[Plans.id]
            when {
                todo.planId != planId ->
                    writes.update(tx, todo.roomId, userId, EntityType.Plan, planId, Plans) { it[Plans.nextStepTodoId] = null }
                row[Plans.nextStep] != step.text || row[Plans.nextStepOwnerId] != step.owner || row[Plans.nextStepDue] != step.due ->
                    writes.update(tx, todo.roomId, userId, EntityType.Plan, planId, Plans) {
                        it[Plans.nextStep] = step.text
                        it[Plans.nextStepOwnerId] = step.owner
                        it[Plans.nextStepDue] = step.due
                    }
            }
        }
    }

    /** 待办做完、删掉（或要彻底删除）：连着它的下一步结束。 */
    fun end(tx: Tx, writes: EntityWrites, roomId: UUID, userId: UUID, todoId: UUID) {
        linked(roomId, todoId).forEach { row ->
            writes.update(tx, roomId, userId, EntityType.Plan, row[Plans.id], Plans) {
                it[Plans.nextStep] = null
                it[Plans.nextStepOwnerId] = null
                it[Plans.nextStepDue] = null
                it[Plans.nextStepTodoId] = null
            }
        }
    }

    private fun linked(roomId: UUID, todoId: UUID) =
        Plans.selectAll().where { (Plans.roomId eq roomId) and (Plans.nextStepTodoId eq todoId) }.toList()

    private fun roomZone(roomId: UUID): ZoneId =
        ZoneId.of(Rooms.select(Rooms.timezone).where { Rooms.id eq roomId }.single()[Rooms.timezone])
}
