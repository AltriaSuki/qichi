package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.qichi.core.designsystem.component.decor.ruledPaper
import app.qichi.core.designsystem.component.decor.marker
import app.qichi.core.designsystem.component.decor.Watermark
import app.qichi.core.designsystem.component.decor.WaxSeal
import app.qichi.core.designsystem.component.decor.Tape
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.component.decor.Stamp
import app.qichi.core.designsystem.component.decor.Sprig
import app.qichi.core.designsystem.component.decor.Seal
import app.qichi.core.designsystem.component.decor.Scene
import app.qichi.core.designsystem.component.decor.Ribbon
import app.qichi.core.designsystem.component.decor.Postmark
import app.qichi.core.designsystem.component.decor.Polaroid
import app.qichi.core.designsystem.component.decor.Illustration
import app.qichi.core.designsystem.component.decor.HandNote
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.LocalQichiTypography
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sky
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.lift

/**
 * 组件陈列（docs/06-design-system.md，开发用）：四种天色下的颜色令牌、字号层级、间距、圆角、浮起阴影、虚线分隔。
 * 截图和设计稿 `New-Spec.dc.html` 对照用；装饰（P10-02）和组件（P10-03）做好后加在这里。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShowcaseScreen(onBack: () -> Unit) {
    var sky by rememberSaveable { mutableStateOf(Sky.Day) }
    val largeText = LocalQichiTypography.current.scale > 1f
    QichiTheme(skyOverride = sky, largeText = largeText) {
        val colors = QichiTheme.colors
        val type = QichiTheme.typography
        Column(Modifier.fillMaxSize().background(colors.background)) {
            BackBar("组件陈列", onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Sky.Dawn to "清晨", Sky.Day to "白天", Sky.Dusk to "黄昏", Sky.Night to "深夜").forEach { (s, label) ->
                        ChoicePill(label, sky == s, { sky = s })
                    }
                }

                SectionLabel("颜色", modifier = Modifier.padding(top = Spacing.l))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(
                        "background" to colors.background, "surface" to colors.surface, "paper" to colors.paper, "card" to colors.card,
                        "ink" to colors.ink, "muted" to colors.muted, "faint" to colors.faint, "line" to colors.line, "line2" to colors.line2,
                        "accent" to colors.accent, "personA" to colors.personA, "personB" to colors.personB,
                    ).forEach { (name, color) -> Swatch(name, color) }
                }

                SectionLabel("字号", modifier = Modifier.padding(top = Spacing.l))
                TypeRow("dateDisplay", type.dateDisplay, "24")
                TypeRow("largeTitle", type.largeTitle, "一起")
                TypeRow("featureTitle", type.featureTitle, "计划")
                TypeRow("barFeature", type.barFeature.copy(color = colors.personA), "计划")
                TypeRow("barTitle", type.barTitle, "秋天去一次海边")
                TypeRow("headline", type.headline, "今天想被好好对待")
                TypeRow("body", type.body, "周六早上八点出发，去东山岛。路上风很大，但大家心情都很好。")
                TypeRow("preview", type.preview.copy(color = colors.muted), "晚上找一家安静的小店吃海鲜，然后在海边坐一会儿。")
                TypeRow("sectionLabel", type.sectionLabel.copy(color = colors.muted), "心情")
                TypeRow("caption", type.caption.copy(color = colors.muted), "小迟 · 今天 19:30")
                TypeRow("numeral", type.numeral, "19:30 · v8 · 1,286 · 09.24")
                TypeRow("tag", type.tag.copy(color = colors.personB), "#旅行/北方")
                TypeRow("hand", type.hand.copy(color = colors.accent), "午后好，需要安慰")
                TypeRow("reading", type.reading, "她不必急着去哪里，先在这里坐一会儿。")
                TypeRow("button", type.button, "用它开始写")
                TypeRow("tab", type.tab, "今天　聊天　一起　我的")

                SectionLabel("间距", modifier = Modifier.padding(top = Spacing.l))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(4, 8, 12, 14, 16, 18, 22, 28, 40).forEach { v ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.width(14.dp).height(v.dp).background(colors.personB.copy(alpha = 0.35f)))
                            Text("$v", style = type.numeral.copy(fontSize = type.caption.fontSize, color = colors.muted))
                        }
                    }
                }

                SectionLabel("圆角与浮起", modifier = Modifier.padding(top = Spacing.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp, 72.dp).lift(colors).clip(QichiShapes.card).background(colors.card), contentAlignment = Alignment.Center) {
                        Text("卡片 14", style = type.caption.copy(color = colors.muted))
                    }
                    Box(Modifier.size(96.dp, 72.dp).clip(QichiShapes.paper).background(colors.paper).border(1.dp, colors.line, QichiShapes.paper), contentAlignment = Alignment.Center) {
                        Text("纸 6", style = type.caption.copy(color = colors.muted))
                    }
                    FeatureTileSample(44.dp, colors.personA)
                }
                Box(Modifier.padding(top = Spacing.l).fillMaxWidth().height(Spacing.l).dashedDivider(colors))
                Text("分隔线是 1dp 虚线（line2）", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.xs))

                // ── 装饰（P10-02，对照 New-Spec.dc.html 的「装饰」一栏） ──
                SectionLabel("装饰", modifier = Modifier.padding(top = Spacing.l))
                DecorGrid()
                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecorGrid() {
    val colors = QichiTheme.colors
    @Composable
    fun Cell(title: String, content: @Composable () -> Unit) {
        Column(Modifier.width(150.dp).padding(bottom = Spacing.m)) {
            Text(title, style = QichiTheme.typography.sectionLabel.copy(color = colors.ink))
            Box(Modifier.padding(top = Spacing.xs).fillMaxWidth().height(150.dp).clip(QichiShapes.card).background(colors.ink.copy(alpha = .03f)), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Cell("胶带 + 拍立得") {
            Polaroid(caption = "绿萝又长了", rotation = -4f, tape = { Tape(Modifier.align(Alignment.TopCenter).offset(y = (-9).dp), color = colors.accent, width = 44.dp, rotation = -6f) }) {
                Illustration(Scene.Window, Modifier.size(88.dp, 80.dp))
            }
        }
        Cell("邮票 + 邮戳") {
            Box {
                Stamp(width = 82.dp, height = 96.dp) { Illustration(Scene.Shelf, Modifier.fillMaxSize()) }
                Postmark("栖迟", "25.09.24", Modifier.offset(x = 44.dp, y = (-10).dp), size = 50.dp)
            }
        }
        Cell("印章") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Seal("栖迟", size = 44.dp)
                Seal("定", size = 30.dp, rotation = 8f)
            }
        }
        Cell("蜡封") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                WaxSeal("迟", size = 56.dp, color = colors.personB)
                WaxSeal("年", size = 46.dp)
            }
        }
        Cell("绿萝线描") { Sprig(width = 130.dp) }
        Cell("荧光笔 + 手写") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("一起", style = QichiTheme.typography.largeTitle.copy(fontSize = QichiTheme.typography.headline.fontSize * 1.3f, color = colors.ink), modifier = Modifier.marker(colors.personA))
                HandNote("我们的小日子", fontSizeSp = 18f)
            }
        }
        Cell("贴纸") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Sticker("需要安慰", rotation = -6f)
                Sticker("第 41 题", color = colors.personB, rotation = 4f, fontSizeSp = 14f)
            }
        }
        Cell("横线纸 + 书签带") {
            Box(Modifier.size(120.dp, 100.dp).lift(colors, QichiShapes.paper).clip(QichiShapes.paper).ruledPaper(lineHeight = 22.dp, top = 6.dp)) {
                Text("窗边的绿萝又长了一截。", style = QichiTheme.typography.caption.copy(color = colors.ink, lineHeight = 22.sp),
                    modifier = Modifier.padding(start = 48.dp, top = 6.dp, end = 20.dp))
                Ribbon(Modifier.align(Alignment.TopEnd).offset(x = (-10).dp, y = (-4).dp), height = 30.dp)
            }
        }
        Cell("水印数字") {
            Box(contentAlignment = Alignment.Center) {
                Watermark("09", fontSizeSp = 96f, alpha = .06f)
                Text("九月", style = QichiTheme.typography.headline.copy(color = colors.ink))
            }
        }
    }
    Text("占位插画", style = QichiTheme.typography.sectionLabel.copy(color = colors.ink))
    FlowRow(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Scene.entries.forEach { scene ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Illustration(scene, Modifier.size(64.dp, 78.dp), shape = QichiShapes.paper)
                Text(scene.label, style = QichiTheme.typography.caption.copy(color = colors.muted))
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(Modifier.size(56.dp).clip(QichiShapes.card).background(color).border(1.dp, colors.line, QichiShapes.card))
        Text(name, style = type.numeral.copy(fontSize = type.caption.fontSize * 0.75f, color = colors.muted), maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TypeRow(name: String, style: TextStyle, sample: String) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(name, style = type.numeral.copy(fontSize = type.caption.fontSize * 0.85f, color = colors.faint))
        Text(sample, style = if (style.color == Color.Unspecified) style.copy(color = colors.ink) else style)
    }
}

@Composable
private fun FeatureTileSample(side: Dp, color: Color) {
    val type = QichiTheme.typography
    Box(Modifier.size(side).clip(QichiShapes.featureTile(side)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Text("×.3", style = type.numeral.copy(fontSize = type.caption.fontSize, color = color))
    }
}
