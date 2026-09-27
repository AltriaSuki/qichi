package app.qichi.feature.widget

import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** 桌面待办组件现在显示什么（P15-02）。 */
sealed interface WidgetContent {
    /** 刚放上桌面、本机数据还没读出来 */
    data object Loading : WidgetContent

    /** 还没登录、还没有房间：点一下打开 App */
    data object NeedsApp : WidgetContent

    /** 当前房间今天的待办，见 [widgetRows]。 */
    data class Today(
        val roomId: UUID,
        val rows: List<WidgetRow>,
        val people: People,
        val today: LocalDate,
        val zone: ZoneId,
    ) : WidgetContent
}

/** 组件的数据：当前房间里跟我有关的、今天到期和过期的待办。只读本机数据库，离线也有。 */
class TodoWidgetSource @Inject constructor(
    private val session: SessionManager,
    private val rooms: RoomRepository,
    private val todos: TodoRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<WidgetContent> =
        combine(session.state.filter { it !is SessionState.Loading }, rooms.currentRoomId) { state, roomId ->
            (state as? SessionState.LoggedIn)?.userId to roomId
        }
            .distinctUntilChanged()
            .flatMapLatest { (me, roomId) ->
                if (me == null || roomId == null) {
                    flowOf(WidgetContent.NeedsApp)
                } else {
                    combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId), todos.observeTodos(roomId)) { room, members, all ->
                        val zone = zoneOf(room?.timezone)
                        val now = Instant.now()
                        val today = todayIn(zone, now)
                        WidgetContent.Today(roomId, widgetRows(all.map { it.value }, me, today, zone, now), People(room, members, me), today, zone)
                    }
                }
            }
}

/** 在组件上勾掉、撤回：和在 App 里勾一样（先写本机、经发件箱发出，离线也行；是计划下一步的，计划也跟着变）。 */
class TodoWidgetActions @Inject constructor(
    private val session: SessionManager,
    private val todos: TodoRepository,
) {
    /** 勾掉。已经做完的、删掉的、本机没有的不动（连点两下只勾一次）。返回是否勾掉了。 */
    suspend fun complete(id: UUID): Boolean {
        if (!signedIn()) return false
        val todo = todos.find(id)?.takeIf { it.doneAt == null && it.deletedAt == null } ?: return false
        todos.complete(todo)
        return true
    }

    /** 撤回刚才的勾：回到没做完（重复待办刚生成的下一次也收回，和 App 里取消完成一样）。返回是否撤回了。 */
    suspend fun reopen(id: UUID): Boolean {
        if (!signedIn()) return false
        val todo = todos.find(id)?.takeIf { it.doneAt != null && it.deletedAt == null } ?: return false
        todos.reopen(todo)
        return true
    }

    // 点组件时 App 可能正在冷启动，登录状态还在读取：等它读出来（和通知上回复一样，P13-04）
    private suspend fun signedIn(): Boolean =
        withTimeoutOrNull(SESSION_WAIT_MS) { session.awaitLoaded() } is SessionState.LoggedIn

    private companion object {
        /** 组件的点击在广播里处理，大约有 10 秒；读登录状态一般不到 1 秒 */
        const val SESSION_WAIT_MS = 5_000L
    }
}

/** 组件由系统创建（不经过 Hilt），从这里拿依赖。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TodoWidgetEntryPoint {
    fun source(): TodoWidgetSource
    fun actions(): TodoWidgetActions
}
