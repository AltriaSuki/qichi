package app.qichi.core.data

import app.qichi.core.designsystem.component.Person
import app.qichi.core.designsystem.component.markCharOf
import app.qichi.shared.api.Member
import app.qichi.shared.api.Room
import java.util.UUID

/** 房间里的两个人：谁是我、谁是对方、各用哪种颜色（创建者 personA，另一位 personB）。 */
data class People(
    val room: Room?,
    val members: List<Member>,
    val myUserId: UUID?,
) {
    val me: Member? get() = members.firstOrNull { it.userId == myUserId }
    val partner: Member? get() = members.firstOrNull { it.userId != myUserId && it.deletedAt == null }

    fun person(userId: UUID?): Person = if (userId != null && userId == room?.createdBy) Person.A else Person.B

    fun name(userId: UUID?): String = members.firstOrNull { it.userId == userId }?.displayName ?: ""

    fun markChar(userId: UUID?): String = markCharOf(name(userId))

    companion object {
        val Empty = People(null, emptyList(), null)
    }
}
