package app.qichi.feature.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.qichi.MainActivity
import app.qichi.R
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiColors
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.colorsFor
import app.qichi.core.designsystem.component.Person
import app.qichi.core.designsystem.skyAt
import app.qichi.navigation.DeepLink
import app.qichi.navigation.Page
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * 桌面待办组件（P15-02，按 design/screens/New-Widget）：当前房间里今天到期和过期的、交给我或两个人的待办。
 * 点圆圈完成后立即消失；点一条打开它的编辑面板，「＋」新建，点「今天」打开待办页。
 * 由系统来画：只用系统字体、纯色和圆角；颜色跟着手机本地时间的天色（刷新时换）。
 */
class TodoWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = EntryPointAccessors.fromApplication(context.applicationContext, TodoWidgetEntryPoint::class.java).source()
        // 先读一次再画，免得每次刷新都先闪一下空的
        val initial = withTimeoutOrNull(INITIAL_WAIT_MS) { source.observe().first() } ?: WidgetContent.Loading
        provideContent {
            val content by source.observe().collectAsState(initial)
            WidgetBody(content)
        }
    }

    companion object {
        /** 两格宽：只列标题 */
        val SMALL = DpSize(110.dp, 110.dp)

        /** 三格宽起：右边还有截止和交给谁 */
        val WIDE = DpSize(250.dp, 110.dp)

        private const val INITIAL_WAIT_MS = 3_000L
    }
}

private val HEADER = 40.dp
private val MARK = 16.dp
private val MARK_RING = 18.dp

@Composable
private fun WidgetBody(content: WidgetContent) {
    val context = LocalContext.current
    val colors = colorsFor(skyAt(LocalTime.now()))
    val small = LocalSize.current.width < TodoWidget.WIDE.width
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_background), colorFilter = tint(colors.card))
            .padding(start = if (small) Spacing.sm else Spacing.ml, end = if (small) Spacing.xs else Spacing.sm, top = Spacing.s, bottom = Spacing.xs),
    ) {
        when (content) {
            WidgetContent.Loading -> Header(colors, small, remaining = 0, onOpen = null, onAdd = null)
            WidgetContent.NeedsApp -> {
                val openApp = actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Header(colors, small, remaining = 0, onOpen = openApp, onAdd = null)
                Message(colors, "打开栖迟登录后，这里会列出今天的待办", null, openApp)
            }
            is WidgetContent.Today -> {
                val room = content.roomId.toString()
                Header(
                    colors, small, remaining = content.rows.remaining(),
                    onOpen = openLink(context, DeepLink.of(room, Page.Todo)),
                    onAdd = openLink(context, DeepLink.of(room, Page.Todo, "new")),
                )
                if (content.rows.isEmpty()) {
                    Message(colors, "今天的事都做完了", if (small) null else "想起什么，点右上角的 ＋ 记下来", null)
                } else {
                    val now = Instant.now()
                    LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                        items(content.rows, itemId = { widgetItemId(it.todo.id) }) { row ->
                            TodoLine(row, content, colors, small, now, openLink(context, DeepLink.of(room, Page.Todo, row.todo.id.toString())))
                        }
                    }
                }
            }
        }
    }
}

/** 「今天」、还有几件（点这一块打开待办页）；右边「＋」新建一件。 */
@Composable
private fun Header(colors: QichiColors, small: Boolean, remaining: Int, onOpen: Action?, onAdd: Action?) {
    Row(GlanceModifier.fillMaxWidth().height(HEADER), verticalAlignment = Alignment.CenterVertically) {
        Row(
            GlanceModifier.defaultWeight().then(onOpen?.let { GlanceModifier.clickable(it) } ?: GlanceModifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("今天", style = TextStyle(color = ColorProvider(colors.ink), fontSize = if (small) 15.sp else 17.sp, fontWeight = FontWeight.Bold))
            if (remaining > 0) {
                Spacer(GlanceModifier.width(Spacing.xs))
                if (small) {
                    Text("$remaining", style = TextStyle(color = ColorProvider(colors.muted), fontSize = 13.sp))
                } else {
                    Box(
                        GlanceModifier.height(22.dp).background(ImageProvider(R.drawable.widget_pill), colorFilter = tint(lerp(colors.card, colors.personB, .12f)))
                            .padding(horizontal = Spacing.xs),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("还有 $remaining 件", style = TextStyle(color = ColorProvider(colors.personB), fontSize = 12.sp, fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
        if (onAdd != null) {
            val size = if (small) 32.dp else 36.dp
            Box(
                GlanceModifier.size(size).background(ImageProvider(R.drawable.widget_circle), colorFilter = tint(colors.ink))
                    .clickable(onAdd).semantics { contentDescription = "新待办" },
                contentAlignment = Alignment.Center,
            ) {
                Image(ImageProvider(R.drawable.widget_plus), contentDescription = null, modifier = GlanceModifier.size(18.dp), colorFilter = tint(colors.background))
            }
        }
    }
}

/**
 * 一行：圆圈（点它勾掉）、标题、截止（晚了的用暮玫瑰色）、交给谁；点这一行别的地方打开它的编辑面板。
 * 刚勾掉的：实心圆圈、标题划线变淡，右边「撤回」。窄的组件只有圆圈和标题。
 */
@Composable
private fun TodoLine(row: WidgetRow, content: WidgetContent.Today, colors: QichiColors, small: Boolean, now: Instant, onOpen: Action) {
    val todo = row.todo
    val id = actionParametersOf(TodoWidgetCallbacks.TODO_ID to todo.id.toString())
    val check = if (small) 18.dp else 20.dp
    // 整行打开编辑面板；圆圈直接完成待办
    Row(
        GlanceModifier.fillMaxWidth().height(if (small) 38.dp else 46.dp).clickable(onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 圆圈的可点范围比圆圈本身大一圈，好点中
        Box(
            GlanceModifier.width(check + Spacing.s).fillMaxHeight()
                .clickable(actionRunCallback<CompleteTodoCallback>(id))
                .semantics { contentDescription = "完成：${todo.title}" },
            contentAlignment = Alignment.CenterStart,
        ) {
            Image(ImageProvider(R.drawable.widget_ring), contentDescription = null, modifier = GlanceModifier.size(check), colorFilter = tint(colors.muted))
        }
        Text(
            todo.title,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = ColorProvider(colors.ink),
                fontSize = if (small) 14.sp else 15.sp,
            ),
            maxLines = 1,
        )
        if (!small) {
            widgetDueLabel(row, content.today, content.zone)?.let { label ->
                Spacer(GlanceModifier.width(Spacing.xs))
                Text(label, style = TextStyle(color = ColorProvider(if (widgetIsLate(row, now)) colors.accent else colors.muted), fontSize = 12.sp))
            }
            Spacer(GlanceModifier.width(Spacing.xs))
            Marks(todo.assigneeId, content.people, colors)
        }
    }
}

/** 交给谁：一个人的圆标；两个人的叠放（我在前）。 */
@Composable
private fun Marks(assigneeId: UUID?, people: People, colors: QichiColors) {
    if (assigneeId != null) {
        Mark(people.markChar(assigneeId), people.person(assigneeId), colors)
        return
    }
    val me = people.me ?: return
    val partner = people.partner
    if (partner == null) {
        Mark(people.markChar(me.userId), people.person(me.userId), colors)
        return
    }
    Box(GlanceModifier.width(MARK + MARK_RING - 4.dp).height(MARK_RING)) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Mark(people.markChar(me.userId), people.person(me.userId), colors)
        }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            // 后一个圆标外面描一圈卡片色，压在前一个上面
            Box(GlanceModifier.size(MARK_RING).background(ImageProvider(R.drawable.widget_circle), colorFilter = tint(colors.card)), contentAlignment = Alignment.Center) {
                Mark(people.markChar(partner.userId), people.person(partner.userId), colors)
            }
        }
    }
}

@Composable
private fun Mark(char: String, person: Person, colors: QichiColors) {
    Box(
        GlanceModifier.size(MARK).background(ImageProvider(R.drawable.widget_circle), colorFilter = tint(if (person == Person.A) colors.personA else colors.personB)),
        contentAlignment = Alignment.Center,
    ) {
        Text(char, style = TextStyle(color = ColorProvider(colors.onPerson), fontSize = 8.sp, fontWeight = FontWeight.Medium))
    }
}

/** 列表空着、还没登录时的一两句话，放在标题下面剩下的地方的中间。 */
@Composable
private fun ColumnScope.Message(colors: QichiColors, title: String, hint: String?, onClick: Action?) {
    Column(
        GlanceModifier.fillMaxWidth().defaultWeight().then(onClick?.let { GlanceModifier.clickable(it) } ?: GlanceModifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = TextStyle(color = ColorProvider(colors.ink), fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
        if (hint != null) Text(hint, style = TextStyle(color = ColorProvider(colors.muted), fontSize = 13.sp, textAlign = TextAlign.Center))
    }
}

/** 把白色的形状染成 [color]（组件里只能这样画带圆角的色块）。 */
private fun tint(color: Color): ColorFilter = ColorFilter.tint(ColorProvider(color))

/** 打开 App 里的某一页（深链，和通知点进来一样）。 */
private fun openLink(context: Context, link: String): Action =
    actionStartActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
