package app.qichi.core.designsystem

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.qichi.R

/**
 * 两种字体都打包在 res/font/（SIL OFL，许可文件在 assets/licenses/），不用可下载字体。
 * 都是可变字重字体：同一个文件按需要的字重实例化。
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int, style: FontStyle = FontStyle.Normal) = Font(
    resId = resId,
    weight = FontWeight(weight),
    style = style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** 思源宋体：全部中文。 */
val NotoSerifSc: FontFamily = FontFamily(
    listOf(200, 300, 400, 500, 600).map { variable(R.font.noto_serif_sc, it) },
)

/** Cormorant Garamond：数字、日期、时间、版本号、罗马数字、「AI」标记。 */
val CormorantGaramond: FontFamily = FontFamily(
    listOf(300, 400, 500).flatMap { w ->
        listOf(
            variable(R.font.cormorant_garamond, w),
            variable(R.font.cormorant_garamond_italic, w, FontStyle.Italic),
        )
    },
)
