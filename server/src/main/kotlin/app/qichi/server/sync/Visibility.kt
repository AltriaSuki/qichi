package app.qichi.server.sync

import app.qichi.server.db.Highlights
import app.qichi.server.db.Messages
import app.qichi.server.db.QnaRounds
import app.qichi.shared.api.Answer
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.wireName
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import java.util.UUID

/**
 * 谁能看见什么（P13-12）。快照、同步、实时提示、导出、时间线、问答、读书和 AI 的资料收集都从这里判断，不各写一份：
 * - 回答：自己的随时可见；对方的要等这一轮揭晓
 * - 读书标记：自己的随时可见；对方的要他打开「共享」。对方改回私有时，同步里按删除下发
 * - 已读位置：只给本人，实时提示也只发本人（不做已读回执）
 * - 撤回的消息：正文在撤回时已清空，照常同步（显示「已撤回」）；但导出、搜索、时间线、AI 的资料里都不出现
 *
 * AI 在聊天里的回答两个人都看得到，所以它查资料时只能拿「两个人都看得到的」：回答要已揭晓（连提问的人自己的也一样），
 * 读书标记要已共享（见 [answersPublic]、[publicHighlight]）。例外是本人愿意：打开了「我没公开的阅读记录」的人，
 * 自己没公开的读书记录也给 AI（P14-02，见 [aiReading]）。
 */
object Visibility {

    fun answer(answer: Answer, viewer: UUID, roundRevealed: Boolean): Boolean = answer.authorId == viewer || roundRevealed

    fun highlight(highlight: Highlight, viewer: UUID): Boolean = highlight.userId == viewer || highlight.shared

    fun readMarker(marker: ReadMarker, viewer: UUID): Boolean = marker.userId == viewer

    /** 一批回答里 [viewer] 能看的（一次查出涉及的轮哪些已揭晓） */
    fun answers(list: List<Answer>, viewer: UUID): List<Answer> {
        val revealed = revealedRounds(list.filter { it.authorId != viewer }.map { it.roundId })
        return list.filter { answer(it, viewer, it.roundId in revealed) }
    }

    fun highlights(list: List<Highlight>, viewer: UUID): List<Highlight> = list.filter { highlight(it, viewer) }

    /** 可以当作「说过的话」拿出来的消息：没进回收站、没撤回（导出、搜索、时间线、AI 的资料） */
    fun quotableMessage(): Op<Boolean> = Messages.deletedAt.isNull() and Messages.retractedAt.isNull()

    /** 这一轮的回答两个人都看得到吗：要已揭晓 */
    fun answersPublic(roundRevealedAt: Instant?): Boolean = roundRevealedAt != null

    /** 两个人都看得到的读书标记：已共享的 */
    fun publicHighlight(): Op<Boolean> = Highlights.shared eq true

    /**
     * AI 能用的读书记录（P14-02）：划线、摘录、AI 解释里已共享的，加上 [openReaders]（打开了「我没公开的阅读记录」的人）
     * 自己没共享的；书签只是位置，不给。「阅读」这一类有人关掉时，调用方根本不该来查。
     */
    fun aiReading(openReaders: Set<UUID>): Op<Boolean> {
        val kinds = Highlights.kind inList AI_READING_KINDS
        return if (openReaders.isEmpty()) kinds and publicHighlight() else kinds and (publicHighlight() or (Highlights.userId inList openReaders))
    }

    private val AI_READING_KINDS = listOf(HighlightKind.Highlight, HighlightKind.Excerpt, HighlightKind.Ai).map { it.wireName }

    /** 这种变化的实时提示只发给谁；为空 = 房间里的人都发 */
    fun hintOnlyFor(type: EntityType, actorId: UUID?): UUID? = if (type == EntityType.ReadMarker) actorId else null

    /** 同步里的一条变化对某人怎么下发 */
    enum class Delivery {
        /** 原样下发 */
        Show,

        /** 当作删除（原来看得到、现在看不到了，比如标记改回私有） */
        AsDeleted,

        /** 不下发（从来没让他看到过，比如对方还没揭晓的回答） */
        Skip,
    }

    /** 给一页变化里的实体逐条判断怎么下发；[entities] 用来一次查出回答所在的轮哪些已揭晓 */
    fun deliveryFor(entities: Collection<SyncEntity>, viewer: UUID): (SyncEntity) -> Delivery {
        val revealed = revealedRounds(entities.filterIsInstance<Answer>().filter { it.authorId != viewer }.map { it.roundId })
        return { entity ->
            when (entity) {
                is ReadMarker -> if (readMarker(entity, viewer)) Delivery.Show else Delivery.Skip
                is Answer -> if (answer(entity, viewer, entity.roundId in revealed)) Delivery.Show else Delivery.Skip
                is Highlight -> if (highlight(entity, viewer)) Delivery.Show else Delivery.AsDeleted
                else -> Delivery.Show
            }
        }
    }

    private fun revealedRounds(roundIds: Collection<UUID>): Set<UUID> {
        val ids = roundIds.toSet()
        if (ids.isEmpty()) return emptySet()
        return QnaRounds.select(QnaRounds.id).where { (QnaRounds.id inList ids) and QnaRounds.revealedAt.isNotNull() }
            .map { it[QnaRounds.id] }.toSet()
    }
}
