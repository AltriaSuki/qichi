# 设计稿

`screens/*.dc.html` 是「栖迟 · 界面总览」画布的源码。画布分两页：

- **新方向**（`New-*.dc.html`，36 张，**现在以它为准**）：字和层次参考 Day One、iA Writer、Bear，颜色沿用晨雾，加了一套统一的装饰。由 `tools/design/gen_screens.py` 生成。
- **晨雾**（其余文件）：上一版，只作对照；第 10 阶段做完后不再更新。

- **看效果**：打开画布 <https://claude.ai/artifact/M871aFHDnXdZ3fUPvrUW7X>（默认打开「新方向」页）。这些文件依赖画布的运行时（`support.js`），直接用浏览器打开本地文件不能正确显示；要本机截图用 `tools/design/render.js`。
- **写代码时**：把它们当作尺寸、颜色、字号、间距的精确参考，直接读源码里的数值。
- **冲突时**：以 `docs/06-design-system.md` 为准，并提醒人类更新画布。
- **同步**：改新方向时先改 `tools/design/gen_screens.py` 再生成，截图检查后同步到画布；画布上有人手改过，就重新导出覆盖本目录。单独提交。

## 新方向

| 文件 | 页面 |
|---|---|
| `New-Spec` | 字、层次与装饰（规范页，1280 宽） |
| `New-Today`（长页）/ `-Dawn` / `-Dusk` / `-Night` | 今天；清晨、黄昏、深夜 |
| `New-Chat` / `New-Chat-Streaming` | 聊天；AI 正在回答 |
| `New-Together` / `-Create` / `-Look` | 一起 · 生活 / 创作 / 回看 |
| `New-Me` | 我的 |
| `New-Mood` `New-Qna` `New-Calendar` `New-Todo` | 心情、问答、日历、待办 |
| `New-Plan-List` / `New-Plan` | 计划首页 / 单个计划 |
| `New-Ideas` / `New-Ideas-Empty` / `New-Tags` | 灵感（软木板）/ 空的时候 / 标签 |
| `New-Writing-List` / `New-Writing` / `New-Writing-Behind` / `New-Writing-Focus` | 写作首页 / 编辑器（署名打开）/ 对方先存了新版 / 专注（深夜） |
| `New-Messages` | 留言 |
| `New-Timeline` / `New-Timeline-Loading` | 时间线（日记式）/ 加载中 |
| `New-Archive` | 档案 |
| `New-Decision-List` / `New-Decision` | 决定首页 / 单个决定 |
| `New-Reading-Shelf` / `New-Reading` | 书架 / 书内 |
| `New-Review-List` / `New-Review` | 审稿首页 / 单份 |
| `New-Summary-List` / `New-Summary` | 总结首页 / 单份 |

## 晨雾（对照）

| 文件 | 页面 |
|---|---|
| `Layout.dc.html` | 布局规则（三层页面） |
| `Main.dc.html`；`Sky-Dawn` / `Sky-Day` / `Sky-Dusk` / `Sky-Night` | 今天；四种天色 |
| `Chat` `Me` | 聊天、我的 |
| `Together` / `Together-Look` | 一起 · 生活 / 回看 |
| `Mood` `Qna` `Calendar` `Plan-List` `Plan` | 心情、问答、日历、计划 |
| `Writing-List` `Writing` `Reading-Shelf` `Reading` `Review-List` `Review` | 写作、阅读、审稿 |
