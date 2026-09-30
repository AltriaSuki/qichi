package app.qichi.core.reading

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * 整个 App 的 FragmentFactory（在 MainActivity.onCreate 里、super.onCreate 之前装上）。
 * Readium 的阅读页是一个带参数的 Fragment：阅读页用自己的工厂直接创建它；
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
        fm.fragments.filter { it.tag == TAG || it.tag?.startsWith("$TAG.") == true }.forEach { removeOwned(fm, it) }
    }

    /** 只清理这一页创建的实例；导航动画期间另一本书也可能已经打开。 */
    fun removeOwned(fm: FragmentManager, fragment: Fragment) {
        if (!fm.isDestroyed && fragment.isAdded) fm.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }

    private val dummy: FragmentFactory by lazy { EpubNavigatorFragment.createDummyFactory() }

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
        if (className == EpubNavigatorFragment::class.java.name) dummy.instantiate(classLoader, className)
        else super.instantiate(classLoader, className)
}
