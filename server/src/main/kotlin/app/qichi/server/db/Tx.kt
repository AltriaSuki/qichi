package app.qichi.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * 一个数据库事务的上下文。[afterCommit] 登记的动作在事务成功提交之后才执行
 * （例如向 WebSocket 广播 changed），事务回滚时不执行。
 */
class Tx internal constructor(val jdbc: JdbcTransaction) {
    internal val afterCommitActions = mutableListOf<suspend () -> Unit>()

    fun afterCommit(action: suspend () -> Unit) {
        afterCommitActions += action
    }
}

/** 在 IO 线程上开一个事务执行 [block]；提交后执行登记的 afterCommit 动作。 */
suspend fun <T> QichiDatabase.tx(block: Tx.() -> T): T {
    val (result, actions) = withContext(Dispatchers.IO) {
        transaction(exposed) {
            val tx = Tx(this)
            val result = tx.block()
            result to tx.afterCommitActions.toList()
        }
    }
    actions.forEach { it() }
    return result
}
