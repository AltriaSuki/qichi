package app.qichi.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 间距只用这些值（docs/06-design-system.md §4）。 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 22.dp
    val xl = 28.dp
    val xxl = 34.dp
    val xxxl = 52.dp

    /** 页边距 */
    val page = xl
    /** 今天页区块之间 */
    val todaySection = xxxl
    /** 详情页区块之间 */
    val detailSection = l
}

object QichiShapes {
    /** 卡片、纸张 */
    val card = RoundedCornerShape(4.dp)
    /** 胶囊按钮、输入框、气泡（全圆） */
    val pill = RoundedCornerShape(percent = 50)
}

object Sizes {
    /** 最小触控区域 */
    val touchTarget = 44.dp
    val listRow = 46.dp
    val listRowTall = 52.dp
    val buttonHeight = 46.dp
    val tabBarHeight = 58.dp
    val backBarHeight = 70.dp
}
