package app.qichi.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.qichi.core.designsystem.QichiTheme

/**
 * 1–10 强度刻度（Mood.dc.html「深浅」）：十根渐高的细线，选中那根加粗为 personA 色，
 * 它左边的是 ink 色、右边的是 line2 色；右侧是 Cormorant 斜体的大数字。
 */
@Composable
fun IntensityTicks(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = QichiTheme.colors
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .weight(1f)
                .selectableGroup(),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (i in 1..10) {
                val selected = i == value
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .selectable(selected = selected, role = Role.RadioButton) { onValueChange(i) }
                        .semantics { contentDescription = "强度 $i" }
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .width(if (selected) 3.dp else 1.dp)
                            .height((7 + 3 * i).dp)
                            .background(
                                when {
                                    selected -> colors.personA
                                    i < value -> colors.ink
                                    else -> colors.line2
                                },
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        }
        Text(
            text = value.toString(),
            style = QichiTheme.typography.numeral.copy(fontSize = 34.sp, fontWeight = FontWeight.W300, color = colors.personA),
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/** 「需要安慰」标记：accent 小圆点 + 文字。 */
@Composable
fun ComfortFlag(modifier: Modifier = Modifier) {
    val colors = QichiTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier
                .size(6.dp)
                .background(colors.accent, CircleShape),
        )
        Text("需要安慰", style = QichiTheme.typography.caption.copy(fontSize = 12.sp, letterSpacing = 0.2.em, color = colors.accent))
    }
}
