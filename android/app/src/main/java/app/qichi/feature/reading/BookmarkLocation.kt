package app.qichi.feature.reading

import kotlin.math.abs
import org.readium.r2.shared.publication.Locator

/** 以资源内的实际位置比较，整本书的 0.5% 可能跨过多页。 */
internal fun sameBookmarkLocation(saved: Locator, current: Locator): Boolean {
    if (saved.href != current.href) return false
    val a = saved.locations
    val b = current.locations
    if (a.progression != null && b.progression != null) return abs(a.progression!! - b.progression!!) < 0.000001
    if (a.totalProgression != null && b.totalProgression != null) return abs(a.totalProgression!! - b.totalProgression!!) < 0.000001
    if (a.position != null && b.position != null) return a.position == b.position
    if (a.fragments.isNotEmpty() || b.fragments.isNotEmpty()) return a.fragments == b.fragments
    return a == b
}
