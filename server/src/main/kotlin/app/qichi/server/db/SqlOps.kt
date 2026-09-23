package app.qichi.server.db

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.stringParam

/** PostgreSQL 的 ILIKE（Exposed 没有内置）；三元组 GIN 索引支持它。模式里的 \\ % _ 已由调用方转义。 */
infix fun Expression<String>.ilike(pattern: String): Op<Boolean> {
    val column = this
    return object : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
            append(column)
            append(" ILIKE ")
            append(stringParam(pattern))
        }
    }
}

/** 把用户输入变成 ILIKE 的「包含」模式：转义 \\ % _。 */
fun containsPattern(query: String): String =
    "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
