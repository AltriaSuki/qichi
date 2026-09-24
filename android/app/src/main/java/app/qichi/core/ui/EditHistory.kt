package app.qichi.core.ui

/**
 * 编辑器的撤销 / 重做（P9-01）。记的是「改之前」的样子（正文 + 选区）。
 *
 * 连续打字合成一步：离上一次改动超过 [groupMillis]、或从打字换成删除（反之亦然）、或光标跳到别处，才另起一步；
 * 格式按钮、粘贴一大段、AI 替换这类 [record] 时传 `force = true`，总是单独一步。最多记 [limit] 步。
 */
class EditHistory(
    private val limit: Int = 200,
    private val groupMillis: Long = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class State(val text: String, val start: Int, val end: Int = start)

    private enum class Kind { Insert, Delete, Other }

    private val undo = ArrayDeque<State>()
    private val redo = ArrayDeque<State>()
    private var lastAt = Long.MIN_VALUE
    private var lastKind = Kind.Other
    private var lastCursor = -1

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** 正文要从 [before] 变成 [after]：需要的话把 [before] 记下来。 */
    fun record(before: State, after: State, force: Boolean = false) {
        if (before.text == after.text) return
        val kind = when {
            after.text.length > before.text.length && after.text.length - before.text.length <= 4 -> Kind.Insert
            after.text.length < before.text.length && before.text.length - after.text.length <= 4 -> Kind.Delete
            else -> Kind.Other
        }
        val t = now()
        val continues = !force && kind != Kind.Other && kind == lastKind && t - lastAt <= groupMillis && before.start == lastCursor
        if (!continues) {
            undo.addLast(before)
            while (undo.size > limit) undo.removeFirst()
        }
        redo.clear()
        lastAt = t
        lastKind = if (force) Kind.Other else kind
        lastCursor = after.start
    }

    /** 撤销：返回要换回去的样子，[current] 进重做栈。没有可撤销的返回 null。 */
    fun undo(current: State): State? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        breakGroup()
        return prev
    }

    fun redo(current: State): State? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        breakGroup()
        return next
    }

    /** 外部换了正文（取到对方的新版本、重基线）：以前的记录不再对得上，清空。 */
    fun clear() {
        undo.clear()
        redo.clear()
        breakGroup()
    }

    private fun breakGroup() {
        lastKind = Kind.Other
        lastCursor = -1
    }
}
