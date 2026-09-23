package app.qichi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.shared.api.PlanStage

/** 当前阶段 = 按顺序第一个还没完成的阶段；都完成了就没有当前阶段。 */
fun currentStage(stages: List<PlanStage>): PlanStage? = stages.sortedBy { it.sortOrder }.firstOrNull { it.doneAt == null }

/**
 * 阶段进度线（设计稿）：完成的阶段是实心小点，当前阶段是 accent 大点，后面的阶段是空心点；
 * 当前阶段之前的连线用 ink，之后的用 line2。给了 [onToggle] 时点阶段名切换完成与否（今天页只看不点）。
 */
@Composable
fun StageTrack(stages: List<PlanStage>, current: PlanStage?, onToggle: ((PlanStage) -> Unit)?, modifier: Modifier = Modifier) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val currentIndex = current?.let { stages.indexOf(it) } ?: stages.size
    Column(modifier.padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            stages.forEachIndexed { i, _ ->
                if (i > 0) Box(Modifier.weight(1f).height(1.dp).background(if (i <= currentIndex) colors.ink else colors.line2))
                when {
                    i < currentIndex -> Box(Modifier.size(7.dp).background(colors.ink, CircleShape))
                    i == currentIndex -> Box(Modifier.size(11.dp).background(colors.personA, CircleShape))
                    else -> Box(Modifier.size(7.dp).border(1.dp, colors.faint, CircleShape))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
            stages.forEachIndexed { i, stage ->
                val isCurrent = i == currentIndex
                Text(
                    stage.title,
                    style = type.caption.copy(
                        letterSpacing = 0.14.em,
                        fontWeight = if (isCurrent) FontWeight.W400 else FontWeight.W300,
                        color = if (isCurrent) colors.ink else colors.muted,
                    ),
                    textAlign = when {
                        stages.size == 1 -> TextAlign.Start
                        i == 0 -> TextAlign.Start
                        i == stages.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (onToggle == null) Modifier else Modifier
                                .heightIn(min = Sizes.touchTarget)
                                .clickable(role = Role.Checkbox, onClickLabel = if (stage.doneAt == null) "标为完成" else "标为未完成") { onToggle(stage) },
                        )
                        .semantics { contentDescription = "${stage.title}，${if (stage.doneAt != null) "已完成" else if (isCurrent) "当前阶段" else "未开始"}" }
                        .padding(top = Spacing.xxs),
                )
            }
        }
    }
}
