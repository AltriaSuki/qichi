package app.qichi.feature.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 系统通过它放置、刷新组件（res/xml/todo_widget_info.xml：每 30 分钟刷新一次，「今天」和天色跟着换）。 */
class TodoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoWidget()
}

/** 组件上点圆圈、点「撤回」：带着是哪一条待办。 */
object TodoWidgetCallbacks {
    val TODO_ID = ActionParameters.Key<String>("todoId")

    /** 取出待办 id、做 [action]；做了就马上刷新桌面上所有这个组件（大小两个都放了的话两个都变）。 */
    internal suspend fun handle(
        context: Context,
        parameters: ActionParameters,
        action: suspend TodoWidgetActions.(UUID) -> Boolean,
    ): Boolean {
        val id = parameters[TODO_ID]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
        val actions = EntryPointAccessors.fromApplication(context.applicationContext, TodoWidgetEntryPoint::class.java).actions()
        val done = actions.action(id)
        if (done) TodoWidget().updateAll(context)
        return done
    }
}

/** 点圆圈完成，立即刷新组件并移除这一条。 */
class CompleteTodoCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TodoWidgetCallbacks.handle(context, parameters) { complete(it) }
    }
}

/** 点「撤回」（或刚勾掉的那个实心圆圈）：回到没做完。 */
class ReopenTodoCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TodoWidgetCallbacks.handle(context, parameters) { reopen(it) }
    }
}

/** 兼容升级前已排队的刷新任务；新版本完成待办后直接刷新，不再排延时任务。 */
class TodoWidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TodoWidget().updateAll(applicationContext)
        return Result.success()
    }
}

/** 本机的待办变了（自己改的、同步来的）、换了房间、登录状态变了：刷新桌面上的组件。App 启动时开始（QichiApplication）。 */
class TodoWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: TodoWidgetSource,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            source.observe()
                .distinctUntilChanged()
                // 同步一次会连着改好几条：停下来再刷新
                .debounce(DEBOUNCE_MS)
                .collect { TodoWidget().updateAll(context) }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
