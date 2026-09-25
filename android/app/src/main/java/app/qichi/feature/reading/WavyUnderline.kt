package app.qichi.feature.reading

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate

/**
 * 书页里对方的标注：波浪下划线（P10-10，按 New-Reading）。Readium 自带的只有底色和直线，这里加一种样式和它的 HTML 模板。
 */
class WavyUnderline(@ColorInt val tint: Int) : Decoration.Style {
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(tint)
    override fun equals(other: Any?): Boolean = other is WavyUnderline && other.tint == tint
    override fun hashCode(): Int = tint

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<WavyUnderline> {
            override fun createFromParcel(source: Parcel) = WavyUnderline(source.readInt())
            override fun newArray(size: Int) = arrayOfNulls<WavyUnderline>(size)
        }

        /** 每一行文字底下一道波浪线（SVG 平铺），颜色取样式里的颜色。 */
        val template = HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { decoration ->
                val tint = (decoration.style as? WavyUnderline)?.tint ?: 0xFF6E8793.toInt()
                val hex = "%06X".format(tint and 0xFFFFFF)
                """<div class="qichi-wavy" style="background-image:url(&quot;data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='8' height='5'><path d='M0 3.5 Q2 0.5 4 3.5 T8 3.5' fill='none' stroke='%23$hex' stroke-width='1.4'/></svg>&quot;)"></div>"""
            },
            stylesheet = ".qichi-wavy { background-repeat: repeat-x; background-position: left bottom; background-size: 8px 5px; transform: translateY(5px); }",
        )
    }
}
