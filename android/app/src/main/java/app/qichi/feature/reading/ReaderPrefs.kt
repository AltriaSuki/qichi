package app.qichi.feature.reading

import app.qichi.core.data.ReadingSettings
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor

/**
 * 本机阅读设置换成 Readium 的排版设置（P19-01）。字号乘在「大字」的 [appScale] 上；
 * 改了行距才不用原书的样式（Readium 的行距只在不用原书样式时生效），默认照原书排。
 */
internal fun epubPreferences(settings: ReadingSettings, paper: Int, ink: Int, appScale: Float): EpubPreferences {
    val custom = settings.lineHeight != ReadingSettings.ORIGINAL
    return EpubPreferences(
        backgroundColor = ReadiumColor(paper),
        textColor = ReadiumColor(ink),
        fontSize = (appScale * settings.fontScale).toDouble(),
        lineHeight = if (custom) settings.lineHeight.toDouble() else null,
        publisherStyles = if (custom) false else null,
        pageMargins = settings.margins.toDouble(),
        scroll = settings.scroll,
    )
}

/**
 * 目录里「正在读的这一章」：和当前位置同一个文件的第一项（不看 # 后面的锚点）；找不到为 -1。
 * [tocHrefs] 与 [current] 都是 href 的字符串形式。
 */
internal fun currentTocIndex(tocHrefs: List<String>, current: String?): Int {
    val file = current?.substringBefore('#')?.takeIf { it.isNotEmpty() } ?: return -1
    return tocHrefs.indexOfFirst { it.substringBefore('#') == file }
}
