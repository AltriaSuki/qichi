package app.qichi.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sky
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.CheckCircle
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.Person
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTabBar
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TabItem
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons

/**
 * 仅调试版可见的「组件陈列」页：四种天色可切换，用来对照设计稿检查组件。
 * 启动：adb shell am start -n app.qichi/.debug.ShowcaseActivity --es sky dusk --ez large true
 */
class ShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialSky = when (intent.getStringExtra("sky")) {
            "dawn" -> Sky.Dawn
            "dusk" -> Sky.Dusk
            "night" -> Sky.Night
            else -> Sky.Day
        }
        val initialLarge = intent.getBooleanExtra("large", false)
        setContent {
            var sky by remember { mutableStateOf(initialSky) }
            var large by remember { mutableStateOf(initialLarge) }
            QichiTheme(skyOverride = sky, largeText = large) {
                Showcase(sky, onSky = { sky = it }, large, onLarge = { large = it }, onBack = ::finish)
            }
        }
    }
}

private val skyNames = listOf(Sky.Dawn to "清晨", Sky.Day to "白天", Sky.Dusk to "黄昏", Sky.Night to "深夜")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Showcase(sky: Sky, onSky: (Sky) -> Unit, large: Boolean, onLarge: (Boolean) -> Unit, onBack: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var tab by remember { mutableIntStateOf(0) }
    var mood by remember { mutableStateOf("疲惫") }
    var todoDone by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        BackBar(title = "组件陈列", onBack = onBack) {
            IconAction(QichiIcons.Search, contentDescription = "搜索", onClick = {})
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.detailSection),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                skyNames.forEach { (s, name) ->
                    ChoicePill(name, selected = s == sky, onClick = { onSky(s) }, modifier = Modifier.weight(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckCircle(checked = large, onCheckedChange = onLarge)
                Text("大字", style = type.bodyLarge.copy(color = colors.ink))
            }

            Column {
                SectionLabel("字号")
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Text("21", style = type.dateDisplay.copy(fontSize = 96.sp, lineHeight = 70.sp, color = colors.ink))
                    Column {
                        Text("一起", style = type.hubTitle.copy(color = colors.ink))
                        Text("有点累", style = type.feeling.copy(color = colors.ink))
                    }
                }
                Text("九月", style = type.pageTitle.copy(color = colors.ink))
                Text("如果明天就能去任何地方，你想去哪里？", style = type.question.copy(color = colors.ink))
                Text("正文：今天连开了三个会，晚上想安静待着。", style = type.body.copy(color = colors.ink))
                Text("辅助信息：周五 · 两小时前", style = type.caption.copy(color = colors.muted))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Bottom) {
                    Text("6", style = type.numeral.copy(fontSize = 28.sp, color = colors.personA))
                    Text("19:30", style = type.numeral.copy(color = colors.muted))
                    Text("v8", style = type.numeral.copy(color = colors.muted))
                    Text("ii", style = type.numeral.copy(color = colors.accent))
                    Text("AI", style = type.numeral.copy(fontSize = 18.sp, color = colors.personB))
                }
            }

            Column {
                SectionLabel("颜色")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        colors.surface, colors.paper, colors.ink, colors.muted, colors.faint,
                        colors.line, colors.line2, colors.accent, colors.personA, colors.personB,
                    ).forEach { Swatch(it) }
                }
            }

            Column {
                SectionLabel("人物") {
                    Text("2", style = type.numeral.copy(color = colors.muted))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PersonMark("栖", Person.A, size = 60.dp)
                    PersonMark("迟", Person.B, size = 26.dp)
                    PersonMark("栖", Person.A, size = 22.dp)
                    PersonMark("迟", Person.B, size = 18.dp)
                    PersonMark("迟", Person.B, size = 26.dp, hollow = true)
                    PersonMarks(listOf("栖" to Person.A, "迟" to Person.B))
                }
            }

            MistCard {
                Text("下一步", style = type.sectionLabel.copy(color = colors.accent, letterSpacing = QichiTheme.typography.sectionLabel.letterSpacing))
                Text(
                    "对比三家民宿的价格和交通",
                    style = type.pageTitle.copy(color = colors.ink, letterSpacing = type.body.letterSpacing * 2),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PersonMark("栖", Person.A, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("周五", style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
                    TextAction("完成", onClick = {})
                }
            }

            Column {
                SectionLabel("此刻")
                FlowRow(maxItemsInEachRow = 4, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("平静", "开心", "期待", "疲惫", "焦虑", "低落", "生气", "委屈").forEach { label ->
                        ChoicePill(label, selected = label == mood, onClick = { mood = label }, modifier = Modifier.weight(1f))
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("我在这里", onClick = {})
                Pill("给你一个拥抱", onClick = {})
                Pill("等你准备好", onClick = {})
            }

            PrimaryButton("记下", onClick = {}, modifier = Modifier.fillMaxWidth())

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("采纳", onClick = {})
                TextAction("问 AI", onClick = {}, color = colors.personB)
                TextAction("不可用", onClick = {}, enabled = false)
            }

            Column {
                SectionLabel("待办")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckCircle(checked = todoDone, onCheckedChange = { todoDone = it })
                    Text(
                        "查往返车次",
                        style = type.bodyLarge.copy(color = if (todoDone) colors.faint else colors.ink),
                        modifier = Modifier.weight(1f),
                    )
                    PersonMarks(listOf("栖" to Person.A, "迟" to Person.B))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
                    CheckCircle(checked = true, onCheckedChange = {}, size = 16.dp)
                    Text("衣物", style = type.body.copy(fontSize = 14.sp, color = colors.faint))
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
        QichiTabBar(
            items = listOf("今天", "聊天", "一起", "我的").mapIndexed { i, label ->
                TabItem(label, selected = i == tab, badge = if (i == 1) 2 else null)
            },
            onSelect = { tab = it },
        )
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color),
    )
}
