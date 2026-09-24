package app.qichi.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.qichi.core.designsystem.icon.QichiIcons

/** 功能色的四档（docs/06-design-system.md §5「功能的颜色和图标」）。 */
enum class FeatureTone { PersonA, PersonB, Accent, Muted }

val FeatureTone.color: Color
    @Composable get() = QichiTheme.colors.let {
        when (this) {
            FeatureTone.PersonA -> it.personA
            FeatureTone.PersonB -> it.personB
            FeatureTone.Accent -> it.accent
            FeatureTone.Muted -> it.muted
        }
    }

/**
 * 每个功能固定的名字、颜色、图标和功能首页标题旁那句手写。只在这里改。
 * [Me] 不是功能，是「我的」下各页顶栏上的小字。
 */
enum class Feature(val title: String, val tone: FeatureTone, val note: String? = null) {
    Mood("心情", FeatureTone.PersonA, "今天怎么样"),
    Qna("问答", FeatureTone.PersonB),
    Plan("计划", FeatureTone.Accent),
    Todo("待办", FeatureTone.PersonB),
    Calendar("日历", FeatureTone.PersonA),
    Ideas("灵感", FeatureTone.Accent, "随手记"),
    Board("留言", FeatureTone.PersonA, "慢慢说"),
    Writing("写作", FeatureTone.PersonB, "一起写"),
    Archive("档案", FeatureTone.Muted),
    Decisions("决定", FeatureTone.Accent, "想清楚再定"),
    Timeline("时间线", FeatureTone.PersonA, "我们的日记"),
    Reading("阅读", FeatureTone.PersonB, "一起读"),
    Review("审稿", FeatureTone.Muted),
    Summary("总结", FeatureTone.Accent, "回头看看"),
    Me("我的", FeatureTone.Muted),
    ;

    val icon: ImageVector
        get() = when (this) {
            Mood -> QichiIcons.Mood
            Qna -> QichiIcons.Qna
            Plan -> QichiIcons.Flag
            Todo -> QichiIcons.Todo
            Calendar -> QichiIcons.Calendar
            Ideas -> QichiIcons.Idea
            Board -> QichiIcons.Mail
            Writing -> QichiIcons.Pen
            Archive -> QichiIcons.Archive
            Decisions -> QichiIcons.Sign
            Timeline -> QichiIcons.Timeline
            Reading -> QichiIcons.Book
            Review -> QichiIcons.Review
            Summary -> QichiIcons.Summary
            Me -> QichiIcons.User
        }

    val color: Color @Composable get() = tone.color
}
