package app.qichi.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/**
 * 把文字里的网址变成可以点的链接（下划线，[color] 色）。打不开（手机上没有浏览器）时什么都不做，不闪退。
 * 没有网址时原样返回，不多建对象。
 */
@Composable
fun rememberLinkified(text: String, color: Color): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    return remember(text, color, uriHandler) {
        val links = findLinks(text)
        if (links.isEmpty()) return@remember AnnotatedString(text)
        val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        val open = LinkInteractionListener { link -> (link as? LinkAnnotation.Url)?.let { runCatching { uriHandler.openUri(it.url) } } }
        buildAnnotatedString {
            append(text)
            links.forEach { addLink(LinkAnnotation.Url(it.url, styles, open), it.start, it.end) }
        }
    }
}
