package app.qichi.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

/**
 * 本机库读出来之后的解析 JSON、筛选、排序放到后台线程做（P17-01）：
 * Room 的查询本来就在它自己的线程，但后面接的 `map` 默认跟着收集方跑在界面线程上，
 * 打开页面、同步进来一批数据时会占住界面线程、掉帧。接在仓库对外的 Flow 最后。
 */
fun <T> Flow<T>.offMain(): Flow<T> = flowOn(Dispatchers.Default)
