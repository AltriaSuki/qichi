package app.qichi.core.ui

import androidx.compose.foundation.lazy.LazyListState

/** 滚到第 [index] 项：平时平滑滚过去，「减少动画」时直接跳到。 */
suspend fun LazyListState.scrollToItemMotion(index: Int, reduceMotion: Boolean) {
    if (reduceMotion) scrollToItem(index) else animateScrollToItem(index)
}
