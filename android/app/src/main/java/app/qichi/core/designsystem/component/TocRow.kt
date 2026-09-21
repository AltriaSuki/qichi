package app.qichi.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.qichi.core.designsystem.QichiTheme

/** 罗马数字（目录序号用）：1 → i，4 → iv。 */
fun romanNumeral(n: Int): String {
    require(n in 1..3999)
    val values = listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc",
        50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
    var rest = n
    return buildString {
        for ((v, s) in values) while (rest >= v) { append(s); rest -= v }
    }
}

/**
 * 目录行：罗马数字 + 标题 + 点线引导 + 右侧数字（如「i 心情 ······ 2」）。
 * [trailing] 为空时只画点线。
 */
@Composable
fun TocRow(
    index: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = romanNumeral(index),
            style = type.numeral.copy(fontSize = 19.sp, color = colors.accent),
            modifier = Modifier.width(26.dp),
        )
        Text(text = title, style = type.tocItem.copy(color = colors.ink))
        val leader = colors.line2
        Canvas(
            Modifier
                .weight(1f)
                .height(1.dp)
                .padding(top = 6.dp),
        ) {
            drawLine(
                color = leader,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())),
            )
        }
        if (trailing != null) {
            Text(text = trailing, style = type.numeral.copy(fontSize = 20.sp, color = colors.muted))
        }
    }
}
