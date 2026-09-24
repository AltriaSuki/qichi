package app.qichi.core.designsystem

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.qichi.R

/**
 * 四种字体都打包在 res/font/（SIL OFL，许可文件在 assets/licenses/），不用可下载字体（docs/06-design-system.md §3，D13）。
 * 思源黑体、思源宋体是可变字重字体：同一个文件按需要的字重实例化。
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** 思源黑体：全部界面和正文。 */
val NotoSansSc: FontFamily = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.noto_sans_sc, it) })

/** IBM Plex Mono：数字、日期、时间、版本号、字数、「AI」、标签、Markdown 符号。 */
val IbmPlexMono: FontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_light, FontWeight.Light),
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/** 思源宋体：只用在书页、书封、印章和蜡封里的字。 */
val NotoSerifSc: FontFamily = FontFamily(listOf(400, 600).map { variable(R.font.noto_serif_sc, it) })

/** 龙藏体：手写短句（问候、贴纸、签名）和拍立得说明、书页批注。 */
val LongCang: FontFamily = FontFamily(Font(R.font.long_cang, FontWeight.Normal))
