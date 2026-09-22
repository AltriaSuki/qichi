package app.qichi.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.CormorantGaramond
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.tsp

/** 题目前的大引号。 */
@Composable
fun QuoteMark(modifier: Modifier = Modifier) {
    Text(
        text = "“",
        style = QichiTheme.typography.question.copy(
            fontFamily = CormorantGaramond,
            fontSize = 80.tsp,
            lineHeight = 80.tsp,
            color = QichiTheme.colors.accent,
        ),
        modifier = modifier,
    )
}

/** 未揭晓的答案：信封折线与蜡封。 */
@Composable
fun WaxSeal(char: String, name: String, modifier: Modifier = Modifier) {
    val colors = QichiTheme.colors
    Box(
        modifier = modifier.fillMaxWidth().height(Sizes.envelopeHeight).clip(QichiShapes.card)
            .background(colors.paper)
            .semantics { contentDescription = "$name 的回答，尚未揭晓" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val fold = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height * 0.56f)
                lineTo(size.width, 0f)
            }
            drawPath(fold, colors.line2, style = Stroke(width = 1.dp.toPx()))
        }
        Box(Modifier.size(Sizes.waxSeal), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(colors.accent)
                drawCircle(colors.ink.copy(alpha = 0.22f), radius = size.minDimension * 0.31f,
                    style = Stroke(width = 1.dp.toPx()))
            }
            Text(char, style = QichiTheme.typography.body.copy(color = colors.onPerson))
        }
    }
}
