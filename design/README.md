# 设计稿

`screens/*.dc.html` 是「栖迟 · 界面总览」画布里 16 张页面的源码（390×844，今天页为 390×2000）。

- **看效果**：打开画布 <https://claude.ai/artifact/M871aFHDnXdZ3fUPvrUW7X>。这些文件依赖画布的运行时（`support.js`），直接用浏览器打开本地文件不能正确显示。
- **写代码时**：把它们当作尺寸、颜色、字号、间距的精确参考，直接读源码里的数值。
- **冲突时**：以 `docs/06-design-system.md` 为准，并提醒人类更新画布。
- **同步**：画布有改动时，重新导出覆盖本目录，单独提交。

| 文件 | 页面 |
|---|---|
| `Main.dc.html` | 今天（长页） |
| `Sky-Dawn` / `Sky-Day` / `Sky-Dusk` / `Sky-Night` | 今天页的四种天色 |
| `Chat.dc.html` | 聊天 |
| `Together.dc.html` / `Together-Look.dc.html` | 一起 · 生活 / 回看 |
| `Me.dc.html` | 我的 |
| `Mood` `Qna` `Plan` `Calendar` | 心情、问答、计划、日历 |
| `Writing` `Reading` `Review` | 共同写作、阅读、审稿 |
