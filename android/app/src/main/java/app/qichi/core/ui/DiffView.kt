package app.qichi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.tsp
import app.qichi.shared.util.Diff

/**
 * 逐行对比：新加的行浅色底、前面「+」；删去的行划掉、前面「−」；没变的行淡色。
 * 没变的连续行太多时折叠成「… 省略 N 行」，只留改动前后各 [context] 行。
 */
@Composable
fun DiffView(lines: List<Diff.Line>, modifier: Modifier = Modifier, context: Int = 2) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val changed = lines.indices.filter { lines[it].kind != Diff.Kind.Same }
    val keep = BooleanArray(lines.size) { i -> changed.any { kotlin.math.abs(it - i) <= context } }
    Column(modifier) {
        if (changed.isEmpty()) {
            Text("没有不同。", style = type.caption.copy(color = colors.muted))
            return@Column
        }
        var i = 0
        while (i < lines.size) {
            if (!keep[i]) {
                var j = i
                while (j < lines.size && !keep[j]) j++
                Text("… 省略 ${j - i} 行", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(vertical = 6.dp))
                i = j
                continue
            }
            val line = lines[i]
            val (mark, desc) = when (line.kind) {
                Diff.Kind.Added -> "+" to "新加"
                Diff.Kind.Removed -> "−" to "删去"
                Diff.Kind.Same -> " " to "没变"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (line.kind == Diff.Kind.Added) colors.personB.copy(alpha = 0.1f) else colors.background.copy(alpha = 0f))
                    .padding(vertical = 2.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$desc：${line.text}" },
            ) {
                Text(mark, style = type.numeral.copy(fontSize = 16.tsp, color = if (line.kind == Diff.Kind.Same) colors.faint else colors.ink),
                    modifier = Modifier.widthIn(min = 20.dp).padding(start = 4.dp))
                Text(
                    line.text.ifEmpty { " " },
                    style = type.body.copy(
                        fontSize = 15.tsp,
                        color = when (line.kind) {
                            Diff.Kind.Same -> colors.muted
                            Diff.Kind.Added -> colors.ink
                            Diff.Kind.Removed -> colors.accent
                        },
                        textDecoration = if (line.kind == Diff.Kind.Removed) TextDecoration.LineThrough else null,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            i++
        }
    }
}
