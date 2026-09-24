package app.qichi.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 间距只用这些值：4、8、12、14、16、18、22、28、40（docs/06-design-system.md §4）。 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val sm = 14.dp
    val m = 16.dp
    val ml = 18.dp
    val l = 22.dp
    val xl = 28.dp
    val xxl = 40.dp

    /** 页边距 */
    val page = xl
    /** 卡片列表的页边距 */
    val cardPage = 24.dp
    /** 今天页区块之间 */
    val todaySection = xxl
    /** 详情页区块之间 */
    val detailSection = l
}

object QichiShapes {
    /** 浮起的卡片、便签、日历格子 */
    val card = RoundedCornerShape(14.dp)
    /** 纸张：书页、文稿、信封、档案卡 */
    val paper = RoundedCornerShape(6.dp)
    /** 胶囊按钮、输入框、气泡、分段切换（全圆） */
    val pill = RoundedCornerShape(percent = 50)

    /** 功能色块：圆角 = 边长 × 0.3 */
    fun featureTile(side: Dp) = RoundedCornerShape(side * 0.3f)
}

object Sizes {
    /** 最小触控区域 */
    val touchTarget = 44.dp
    /** 列表行 50–60 */
    val listRow = 50.dp
    val listRowTall = 56.dp
    val buttonHeight = 46.dp
    val tabBarHeight = 62.dp
    val backBarHeight = 70.dp
    val envelopeHeight = 100.dp
    val waxSeal = 46.dp
    val calendarCellHeight = 58.dp
    val calendarDate = 36.dp
    val calendarDot = 5.dp
    val calendarLabelWidth = 58.dp
}
