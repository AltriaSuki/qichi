package app.qichi.core.reading

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * 整个 App 的 FragmentFactory（在 MainActivity.onCreate 里、super.onCreate 之前装上）。
 * Readium 的阅读页是一个带参数的 Fragment：阅读页打开时由它提供真正的工厂；
 * 旋转屏幕、系统回收后恢复时先放一个空的替身，阅读页随后会换成新的。
 */
object ReaderFragments : FragmentFactory() {
    /** 阅读页 Fragment 的 tag */
    const val TAG = "qichi.reader"

    /**
     * Activity 重建时（super.onCreate 之后立即调用）：移除系统恢复出来的阅读页。
     * Readium 不支持恢复它（在 onResume 时会报错）；阅读器页面随后会放一个新的，从刚才的位置接着读。
     */
    fun dropRestored(fm: FragmentManager) {
        fm.findFragmentByTag(TAG)?.let { fm.beginTransaction().remove(it).commitNowAllowingStateLoss() }
    }

    /** 当前阅读页要用的工厂（阅读页负责设置和清空） */
    var epub: FragmentFactory? = null

    private val dummy: FragmentFactory by lazy { EpubNavigatorFragment.createDummyFactory() }

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
        if (className == EpubNavigatorFragment::class.java.name) (epub ?: dummy).instantiate(classLoader, className)
        else super.instantiate(classLoader, className)
}
