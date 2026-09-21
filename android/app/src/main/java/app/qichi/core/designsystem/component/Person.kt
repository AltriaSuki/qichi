package app.qichi.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.qichi.core.designsystem.NotoSerifSc
import app.qichi.core.designsystem.QichiTheme

/** 房间里的两个人：创建者用 personA，另一位用 personB（与名字无关）。 */
enum class Person { A, B }

@Composable
fun Person.color(): Color = when (this) {
    Person.A -> QichiTheme.colors.personA
    Person.B -> QichiTheme.colors.personB
}

/** 人物标记里显示的单字：取显示名的最后一个字（「阿栖」→「栖」）。 */
fun markCharOf(displayName: String): String =
    displayName.trim().let { name ->
        if (name.isEmpty()) "?" else name.substring(name.offsetByCodePoints(name.length, -1))
    }

/**
 * 圆形人物标记，内含单字。
 * @param hollow 虚线空心（未确认，如问答里对方还没回答）
 * @param size 常用 18 / 22 / 26 / 60
 */
@Composable
fun PersonMark(
    char: String,
    person: Person,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    hollow: Boolean = false,
) {
    val color = person.color()
    val fontSize = (size.value * 0.46f).sp
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (hollow) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 1.dp.toPx()
                drawCircle(
                    color = color,
                    radius = (this.size.minDimension - stroke) / 2,
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))),
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            text = char,
            style = TextStyle(
                fontFamily = NotoSerifSc,
                fontWeight = FontWeight.W400,
                fontSize = fontSize,
                lineHeight = fontSize,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                color = if (hollow) color else QichiTheme.colors.onPerson,
            ),
        )
    }
}

/** 两个标记叠放，表示「两个人一起」；后一个带一圈 background 色描边压在前一个上。 */
@Composable
fun PersonMarks(
    marks: List<Pair<String, Person>>,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val ring = 2.dp
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        marks.forEachIndexed { index, (char, person) ->
            if (index == 0) {
                PersonMark(char, person, size = size)
            } else {
                Box(
                    Modifier
                        .offset(x = (-5).dp * index)
                        .size(size + ring * 2)
                        .clip(CircleShape)
                        .background(QichiTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    PersonMark(char, person, size = size)
                }
            }
        }
    }
}
