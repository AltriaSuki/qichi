package app.qichi.feature.todo

import app.qichi.core.ui.TodoGroup
import java.util.UUID

/** 编辑面板打开哪一条：新建、改这一条、还在读（先等等）、找不到了（关掉）。 */
sealed interface TodoEditTarget {
    data object New : TodoEditTarget
    data class Existing(val group: TodoGroup) : TodoEditTarget
    data object Waiting : TodoEditTarget
    data object Gone : TodoEditTarget

    companion object {
        /** 新建用的 key（也是深链 `todo/new` 里的那一段） */
        const val NEW = "new"

        /**
         * [key] 是 [NEW] 或一条待办的 id（页面里点开的，或桌面组件点进来的深链 `todo/{id}`，P15-02）。
         * 从桌面组件点进来时列表可能还没从本机读出来（[loaded] 为假）：先等；读出来了还找不到（已经删了、不是 id）才关掉。
         */
        fun of(key: String, loaded: Boolean, find: (UUID) -> TodoGroup?): TodoEditTarget {
            if (key == NEW) return New
            val id = runCatching { UUID.fromString(key) }.getOrNull() ?: return Gone
            find(id)?.let { return Existing(it) }
            return if (loaded) Gone else Waiting
        }
    }
}
