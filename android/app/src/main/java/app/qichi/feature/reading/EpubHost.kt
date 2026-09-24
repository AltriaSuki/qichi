package app.qichi.feature.reading

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import app.qichi.core.reading.ReaderFragments
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** 选中文字后菜单里的一项。 */
data class SelectionAction(val id: Int, val label: String, val onClick: (EpubNavigatorFragment) -> Unit)

private const val TAG = ReaderFragments.TAG

/**
 * 把 Readium 的阅读页（Fragment）放进 Compose。每次进入都换成新的 Fragment（旋转屏幕后恢复出来的替身会被替换）。
 * 选中文字时弹出系统的文字菜单，里面是 [selectionActions]。
 */
@Composable
fun EpubHost(
    publication: Publication,
    initialLocator: Locator?,
    preferences: EpubPreferences,
    selectionActions: List<SelectionAction>,
    onReady: (EpubNavigatorFragment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = androidx.activity.compose.LocalActivity.current as FragmentActivity
    val containerId = remember { View.generateViewId() }
    AndroidView(factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } }, modifier = modifier)

    DisposableEffect(publication) {
        val fm = activity.supportFragmentManager
        var navigator: EpubNavigatorFragment? = null
        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                selectionActions.forEachIndexed { i, a -> menu.add(Menu.NONE, a.id, i, a.label) }
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val action = selectionActions.firstOrNull { it.id == item.itemId } ?: return false
                navigator?.let(action.onClick)
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
        ReaderFragments.epub = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = preferences,
            configuration = EpubNavigatorFragment.Configuration { selectionActionModeCallback = callback },
        )
        fm.findFragmentByTag(TAG)?.let { fm.beginTransaction().remove(it).commitNowAllowingStateLoss() }
        fm.beginTransaction().setReorderingAllowed(true)
            .replace(containerId, EpubNavigatorFragment::class.java, null, TAG)
            .commitNowAllowingStateLoss()
        navigator = fm.findFragmentByTag(TAG) as? EpubNavigatorFragment
        navigator?.let(onReady)
        onDispose {
            fm.findFragmentByTag(TAG)?.let { if (!fm.isDestroyed) fm.beginTransaction().remove(it).commitNowAllowingStateLoss() }
            ReaderFragments.epub = null
        }
    }
}
