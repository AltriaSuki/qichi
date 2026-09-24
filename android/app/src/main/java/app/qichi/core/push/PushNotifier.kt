package app.qichi.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.qichi.MainActivity
import app.qichi.R
import app.qichi.core.designsystem.component.markCharOf
import app.qichi.shared.api.PushPayload
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 把推送显示成系统通知，像 QQ 那样：
 * - 聊天消息走「聊天消息」渠道（会弹横幅），同一个聊天的消息按聊天记录的样子叠在一条通知里，带对方的圆标，可以直接回复
 * - 其它动态（心情、待办、日程……）走「房间动态」渠道，同一类只留最新一条
 * 点开都走深链；打开聊天页时清掉聊天通知（[clearChat]）。
 */
object PushNotifier {
    private const val CHANNEL_CHAT = "chat"
    private const val CHANNEL_ROOM = "room"
    private const val HISTORY = "qichi-push-history"
    private const val HISTORY_MAX = 10

    const val KEY_REPLY = "reply"
    const val EXTRA_TAG = "tag"
    const val EXTRA_ROOM = "roomId"

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 最近弹过的（ntfy 和内置通知可能各送来一份同样的，只弹一次） */
    private val recent = ArrayDeque<String>()

    @Synchronized
    private fun seen(payload: PushPayload): Boolean {
        val key = payload.messageId?.toString() ?: "${payload.tag}|${payload.body}|${payload.sentAt}"
        if (key in recent) return true
        recent.addLast(key)
        while (recent.size > 50) recent.removeFirst()
        return false
    }

    fun show(context: Context, payload: PushPayload) {
        if (!canNotify(context) || seen(payload)) return
        ensureChannels(context)
        if (payload.kind == PushPayload.KIND_MESSAGE && payload.sender != null) showMessage(context, payload) else showEvent(context, payload)
    }

    /** 打开了这个房间的聊天：聊天通知清掉，叠着的记录也忘掉。 */
    fun clearChat(context: Context, roomId: UUID) {
        val tag = "$roomId:chat"
        NotificationManagerCompat.from(context).cancel(tag, 0)
        context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).edit().remove(tag).apply()
    }

    // ── 聊天消息 ──

    private data class Line(val id: String, val sender: String, val senderId: String?, val creator: Boolean, val text: String, val at: Long, val mine: Boolean)

    private fun history(context: Context, tag: String): MutableList<Line> {
        val raw = context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).getString(tag, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                Line(o.getString("id"), o.getString("sender"), o.optString("senderId").ifEmpty { null }, o.optBoolean("creator"),
                    o.getString("text"), o.getLong("at"), o.optBoolean("mine"))
            }
        }.getOrDefault(mutableListOf())
    }

    private fun saveHistory(context: Context, tag: String, lines: List<Line>) {
        val array = JSONArray()
        lines.takeLast(HISTORY_MAX).forEach { l ->
            array.put(JSONObject().put("id", l.id).put("sender", l.sender).put("senderId", l.senderId ?: "").put("creator", l.creator)
                .put("text", l.text).put("at", l.at).put("mine", l.mine))
        }
        context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).edit().putString(tag, array.toString()).apply()
    }

    private fun showMessage(context: Context, p: PushPayload) {
        val lines = history(context, p.tag)
        val id = p.messageId?.toString() ?: UUID.randomUUID().toString()
        if (lines.none { it.id == id }) {
            lines += Line(id, p.sender!!, p.senderId?.toString(), p.senderIsCreator, p.body, p.sentAt?.toEpochMilli() ?: System.currentTimeMillis(), mine = false)
        }
        saveHistory(context, p.tag, lines)
        render(context, p.tag, p.link, p.roomId, p.title, lines)
    }

    /** 在通知里回复之后：把自己的话接在记录后面，通知留着（和 QQ 一样看得到自己回了什么）。 */
    fun appendMyReply(context: Context, tag: String, text: String, myName: String, iAmCreator: Boolean) {
        val lines = history(context, tag)
        if (lines.isEmpty()) return
        lines += Line(UUID.randomUUID().toString(), myName, null, iAmCreator, text, System.currentTimeMillis(), mine = true)
        saveHistory(context, tag, lines)
        val last = lines.last { !it.mine }
        val roomId = tag.substringBefore(':').let { runCatching { UUID.fromString(it) }.getOrNull() }
        render(context, tag, "qichi://room/${tag.substringBefore(':')}/chat", roomId, last.sender, lines, silent = true)
    }

    private fun render(context: Context, tag: String, link: String, roomId: UUID?, title: String, lines: List<Line>, silent: Boolean = false) {
        // 自己：用自己的圆标（最近一条自己的回复里记着名字和颜色）
        val mine = lines.lastOrNull { it.mine }
        val me = Person.Builder().setName("我").setKey("me")
            .apply { mine?.let { setIcon(IconCompat.createWithBitmap(avatar(it.sender, it.creator))) } }
            .build()
        val style = NotificationCompat.MessagingStyle(me)
        val people = HashMap<String, Person>()
        lines.forEach { l ->
            val person = if (l.mine) me else people.getOrPut(l.senderId ?: l.sender) {
                Person.Builder().setName(l.sender).setKey(l.senderId ?: l.sender).setIcon(IconCompat.createWithBitmap(avatar(l.sender, l.creator))).build()
            }
            style.addMessage(NotificationCompat.MessagingStyle.Message(l.text, l.at, person))
        }
        val unread = lines.takeLastWhile { !it.mine }.size
        val builder = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(lines.last().text)
            .setContentIntent(open(context, tag, link))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(unread)
            .setOnlyAlertOnce(silent)
            // 锁屏上：系统设置允许显示敏感内容时才显示原文
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_CHAT).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("栖迟").setContentText(if (unread > 1) "$unread 条新消息" else "新消息").build(),
            )
        lines.lastOrNull { !it.mine }?.let { builder.setLargeIcon(avatar(it.sender, it.creator)) }
        if (roomId != null) builder.addAction(replyAction(context, tag, roomId))
        notify(context, tag, builder)
    }

    private fun replyAction(context: Context, tag: String, roomId: UUID): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_ROOM, roomId.toString())
        val pending = PendingIntent.getBroadcast(
            context, tag.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("回复").build()
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, "回复", pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    // ── 其它动态 ──

    private fun showEvent(context: Context, p: PushPayload) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ROOM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(p.title)
            .setContentText(p.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.body))
            .setContentIntent(open(context, p.tag, p.link))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        p.sender?.let { builder.setLargeIcon(avatar(it, p.senderIsCreator)) }
        notify(context, p.tag, builder)
    }

    private fun open(context: Context, tag: String, link: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, tag.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notify(context: Context, tag: String, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(tag, 0, builder.build())
        } catch (_: SecurityException) {
            // 权限刚被收回
        }
    }

    /** 和 App 里一样的圆标：房间创建者暮玫瑰色，另一位青灰色，中间一个字。 */
    private fun avatar(name: String, creator: Boolean): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (creator) 0xFFA8625F.toInt() else 0xFF4F6B7A.toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.46f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val y = size / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(markCharOf(name), size / 2f, y, text)
        return bitmap
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_CHAT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CHAT, "聊天消息", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "对方发来的消息，会在屏幕上方弹出"
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ROOM) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ROOM, "房间动态", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "对方记下心情、加了待办和日程、写了留言等"
                },
            )
        }
    }
}
