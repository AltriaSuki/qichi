package app.qichi.shared.api

import app.qichi.shared.model.TimelineEntryKind
import kotlinx.serialization.Serializable

/**
 * 时间线上的一件事：定下的决定、记下的灵感、完成的计划、两个人都选中的照片。
 * [refId] 是原来那条记录的 id（照片是文件 id），点开时跳过去。
 */
@Serializable
data class TimelineEntry(
    val kind: TimelineEntryKind,
    val refId: Id,
    val at: Timestamp,
    val title: String,
    val detail: String? = null,
    val authorId: Id? = null,
    /** 只有照片有 */
    val file: FileMeta? = null,
)

/** 某个月有几件事（月份按房间时区算）。 */
@Serializable
data class TimelineMonthCount(val year: Int, val month: Int, val count: Int)

@Serializable
data class TimelinePage(
    val year: Int,
    val month: Int,
    /** 按时间从早到晚 */
    val entries: List<TimelineEntry>,
    /** 所有有内容的月份，新的在前 */
    val months: List<TimelineMonthCount>,
)

/** 谁选中了哪张照片。 */
@Serializable
data class TimelinePick(val fileId: Id, val userId: Id, val createdAt: Timestamp)

/** 某一天聊天里发过的一张照片（「一年前的今天」用）。 */
@Serializable
data class DayPhoto(val messageId: Id, val file: FileMeta, val authorId: Id?, val at: Timestamp)

/** 「一年前的今天」要从服务端取的部分：那天的照片（更早的聊天记录不一定在手机上）。 */
@Serializable
data class OnThisDay(val date: Day, val photos: List<DayPhoto>)
