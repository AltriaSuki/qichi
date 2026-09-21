# 06 · 设计系统「晨雾」

> 视觉参考：`design/screens/*.html`（用浏览器打开即可看，字体会从网上加载）。
> 本文件是把设计稿翻译成代码时的规则；两者冲突时以本文件为准，并提醒人类更新设计稿。

## 1. 气质

参考弗里德里希《雾海上的漫游者》与 Kinfolk 杂志的留白：

- **雾**：浅色、低饱和、层层叠叠的半透明白，不用描边框。
- **留白**：页边距 28dp，区块之间留很大的空；每屏只有一个视觉焦点。
- **克制**：几乎不用分割线，靠间距分组；只有一种强调色（暮玫瑰）。
- **文学**：细字重的宋体做大字，西文衬线斜体写数字。
- **不说明**：界面上不写解释性文字，状态用图形和位置表达。

## 2. 四种天色

按**手机本地时间**切换，整页 800ms 淡入淡出；「减少动画」开启时直接切换。

| 天色 | 时段 |
|---|---|
| 清晨 dawn | 05:00–08:00 |
| 白天 day | 08:00–17:00 |
| 黄昏 dusk | 17:00–19:30 |
| 深夜 night | 19:30–05:00 |

| 令牌 | 清晨 | 白天 | 黄昏 | 深夜 | 用途 |
|---|---|---|---|---|---|
| `background` | `#EEE7E4` | `#E9ECEA` | `#ECE3D6` | `#1B2027` | 页面底色 |
| `surface` | 白 60% | 白 62% | 白 55% | 白 5% | 雾层卡片、输入框、气泡 |
| `paper` | `#F7F2F0` | `#F5F6F3` | `#F6F0E6` | `#222830` | 文稿纸、书页、信封 |
| `ink` | `#2E2F38` | `#2B323A` | `#2F2B28` | `#E3E6E4` | 正文 |
| `muted` | `#655E66` | `#5C646C` | `#665D55` | `#9CA5AD` | 次要文字（已验证对比度 ≥ 4.5:1） |
| `faint` | `#A69BA0` | `#9CA3A7` | `#A89C8E` | `#5F6973` | 装饰、已完成项、占位（**不用于需要阅读的文字**） |
| `line` | `#DDD3D1` | `#D6DBD9` | `#DDD2C3` | `#2B323B` | 极少量分隔 |
| `line2` | `#C8BCBC` | `#BEC5C5` | `#C9BBA8` | `#3A434D` | 点线引导、未完成节点 |
| `accent` | `#9A5552` | `#9A5552` | `#94523A` | `#D8A09C` | 强调文字：「需要安慰」「下一步」「采纳」、罗马数字 |
| `personA` 阿栖 | `#A8625F` | `#A8625F` | `#A55E45` | `#D39A96` | 人物圆标、已完成勾、进度 |
| `personB` 小迟 | `#4F6B7A` | `#4F6B7A` | `#4F6B7A` | `#93AEBD` | 人物圆标、AI 标记、对方的高亮 |
| `onPerson` | 白 | 白 | 白 | `#1B2027` | 人物圆标上的字 |

> 「阿栖是玫瑰色、小迟是雾蓝」只是设计稿里的示例。实际规则：**房间的创建者用 personA，另一位用 personB**，与名字无关。

对应的 Kotlin 结构（放在 `core/designsystem/Sky.kt`）：

```kotlin
enum class Sky { Dawn, Day, Dusk, Night }

@Immutable
data class QichiColors(
    val background: Color, val surface: Color, val paper: Color,
    val ink: Color, val muted: Color, val faint: Color,
    val line: Color, val line2: Color, val accent: Color,
    val personA: Color, val personB: Color, val onPerson: Color,
    val isDark: Boolean,
)

val DayColors = QichiColors(
    background = Color(0xFFE9ECEA), surface = Color.White.copy(alpha = 0.62f), paper = Color(0xFFF5F6F3),
    ink = Color(0xFF2B323A), muted = Color(0xFF5C646C), faint = Color(0xFF9CA3A7),
    line = Color(0xFFD6DBD9), line2 = Color(0xFFBEC5C5), accent = Color(0xFF9A5552),
    personA = Color(0xFFA8625F), personB = Color(0xFF4F6B7A), onPerson = Color.White,
    isDark = false,
)
// DawnColors、DuskColors、NightColors 按上表同样写出

fun skyAt(localTime: LocalTime): Sky = when {
    localTime < LocalTime.of(5, 0) -> Sky.Night
    localTime < LocalTime.of(8, 0) -> Sky.Dawn
    localTime < LocalTime.of(17, 0) -> Sky.Day
    localTime < LocalTime.of(19, 30) -> Sky.Dusk
    else -> Sky.Night
}
```

通过 `CompositionLocal` 提供 `LocalQichiColors`，每分钟检查一次是否需要切换。Material 3 的 `ColorScheme` 由 `QichiColors` 映射生成，只为了让内置组件不出错。深夜时状态栏图标用浅色。

## 3. 字体

两种字体都**打包进 App**（`res/font/`），不用可下载字体：

| 字体 | 用途 |
|---|---|
| Noto Serif SC（思源宋体，可变字重，至少 200 / 300 / 400 / 600） | 全部中文 |
| Cormorant Garamond（300 / 400 / 500，含斜体） | 数字、日期、时间、版本号、罗马数字、「AI」标记 |

字号层级（sp，行高为倍数，字距为 em）：

| 名称 | 字体 · 字重 | 字号 | 行高 | 字距 | 例子 |
|---|---|---|---|---|---|
| `dateDisplay` | Cormorant 300 | 156 | 0.72 | -0.02 | 今天页的「21」 |
| `hubTitle` | 宋体 200 | 46 | 1.2 | 0.32 | 「一起」 |
| `pageTitle` | 宋体 300 | 19–20 | 1.5 | 0.2 | 返回条标题、「九月」 |
| `feeling` | 宋体 200 | 26–32 | 1.3 | 0.08 | 心情词「有点累」 |
| `question` | 宋体 200–300 | 21–23 | 1.8 | 0.04 | 问答题目 |
| `tocItem` | 宋体 200 | 23 | 1.4 | 0.2 | 「一起」目录条目 |
| `body` | 宋体 300 | 15–16 | 1.8 | 0.03 | 正文、列表 |
| `reading` | 宋体 300 | 17 | 2.05–2.1 | 0.03 | 书页、文稿 |
| `sectionLabel` | 宋体 400 | 12 | 1.6 | 0.34 | 「心情」「待办」小标题（muted） |
| `caption` | 宋体 300 | 12–13 | 1.5 | 0.1 | 辅助信息（muted） |
| `numeral` | Cormorant 斜体 | 15–28 | 1 | 0 | 「6」「19:30」「v8」「ii」 |
| `tab` | 宋体 300 / 选中 400 | 15 | 1 | 0.3 | 底部标签 |

「大字」显示模式：以上全部 × 1.2，`dateDisplay` 不变。

## 4. 间距、圆角、尺寸

- 间距只用这些值：4、8、12、16、22、28、34、52（dp）。页边距 28；今天页区块之间 52；详情页区块之间 22。
- 圆角：卡片和纸张 4dp；胶囊按钮、输入框、气泡 22–23dp（全圆）；人物标记为圆形。
- 触控区域最小 44dp；列表行 46–52dp。
- 卡片不加描边、不加投影；纸张（文稿、书页、审稿预览）可用一道极淡的投影 `0 18 40 rgba(43,50,58,.06)`。

## 5. 组件清单（`core/designsystem/component/`）

| 组件 | 说明 | 设计稿 |
|---|---|---|
| `QichiTabBar` | 四个文字标签，选中项上方 4dp 的 accent 圆点；聊天未读数用 Cormorant 斜体小数字 | 任一主标签页 |
| `BackBar` | 返回箭头 + 标题 + 右侧图标按钮 | 任一详情页 |
| `SectionLabel` | 12sp、muted、字距 0.34em 的小标题，可带右侧内容 | Main |
| `PersonMark` | 圆形人物标记，内含单字；`hollow` 为虚线空心（未确认） | 多处 |
| `PersonMarks` | 两个标记叠放（两人一起） | 待办、安排 |
| `MistCard` | surface 背景、4dp 圆角、无描边 | 问答、下一步 |
| `Pill` | surface 背景的胶囊按钮（心情回应、情绪选择） | Mood |
| `PrimaryButton` | ink 底、background 色字，胶囊形 | Mood「记下」 |
| `TextAction` | 无背景的文字按钮，默认 accent 色 | 「采纳」「我的回答」 |
| `CheckCircle` | 圆形复选框；完成后 personA 实心 + 白勾 | 待办 |
| `StageLine` | 计划阶段：已完成实心点、当前点带光晕、未开始空心 | Plan |
| `ComfortFlag` | accent 小圆点 +「需要安慰」 | Main、Mood |
| `WaxSeal` | 蜡封，用于未揭晓的回答 | Qna |
| `QuoteMark` | Cormorant 大引号，accent 色 | Main、Qna |
| `FogSeaHero` | 今天页主视觉：有照片时显示照片；没有时显示按天色绘制的雾海插画（Canvas 绘制，参考设计稿 SVG） | Main、Sky-* |
| `TocRow` | 目录行：罗马数字 + 标题 + 点线 + 右侧数字 | Together |
| `IntensityTicks` | 1–10 强度刻度，选中项加粗为 personA 色 | Mood |

## 6. 页面与设计稿对照

| 页面 | 设计稿文件 |
|---|---|
| 今天 | `Main.dc.html`（长页）；四种天色：`Sky-Dawn/Day/Dusk/Night.dc.html` |
| 聊天 | `Chat.dc.html` |
| 一起 · 生活 / 回看 | `Together.dc.html` / `Together-Look.dc.html` |
| 我的 | `Me.dc.html` |
| 心情、问答、计划、日历 | `Mood` `Qna` `Plan` `Calendar` |
| 共同写作、阅读、审稿 | `Writing` `Reading` `Review` |

设计稿里没有画的页面（留言、档案、决定、时间线、总结、回收站、设置、登录），按本文件的规则自行设计，保持同样的留白与克制；做完截图给人类确认。

## 7. 动效

- 只在三处用动效：天色切换（800ms 淡入）、页面进出（200ms 淡入 + 8dp 位移）、问答揭晓（两份答案同时淡入）。
- 不用弹跳、不用夸张缩放。
- 「减少动画」开启时全部改为直接切换。
