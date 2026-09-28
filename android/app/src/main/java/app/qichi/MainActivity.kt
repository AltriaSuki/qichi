package app.qichi

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.DisplaySettingsStore
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sky
import app.qichi.core.designsystem.colorsFor
import app.qichi.core.designsystem.skyAt
import app.qichi.core.reading.PageKeys
import app.qichi.core.reading.ReaderFragments
import app.qichi.core.share.ShareInbox
import app.qichi.core.share.sharedContent
import app.qichi.navigation.DeepLink
import app.qichi.navigation.QichiRoot
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalTime
import javax.inject.Inject

/** 唯一的 Activity，承载整个 App 的导航。深链、别的 App 分享进来的内容（P16-03）从 onCreate / onNewIntent 进来。是 FragmentActivity，因为阅读页用了 Readium 的 Fragment。 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var displaySettings: DisplaySettingsStore
    @Inject lateinit var shareInbox: ShareInbox

    private var pendingLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 阅读页（Readium）是 Fragment：恢复时要用能造出它的工厂，必须在 super.onCreate 之前装上
        supportFragmentManager.fragmentFactory = ReaderFragments
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) ReaderFragments.dropRestored(supportFragmentManager)
        enableEdgeToEdge()
        // 窗口底色按现在的天色：夜里打开不先闪一下白天的浅色（主题里写死的是白天的底色）
        window.setBackgroundDrawable(ColorDrawable(colorsFor(skyAt(LocalTime.now())).background.toArgb()))
        // 手机设置里关掉了动画（开发者选项或无障碍的「移除动画」）：App 里也直接切换
        val systemNoAnimation = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (savedInstanceState == null) {
            pendingLink = DeepLink.parse(intent?.dataString)
            intent?.sharedContent()?.let(shareInbox::offer)
        }
        // 仅调试版：adb shell am start … --es qichi.sky dusk 固定天色，便于截图
        val skyOverride = if (BuildConfig.DEBUG) {
            when (intent?.getStringExtra("qichi.sky")) {
                "dawn" -> Sky.Dawn
                "day" -> Sky.Day
                "dusk" -> Sky.Dusk
                "night" -> Sky.Night
                else -> null
            }
        } else {
            null
        }
        setContent {
            // 读到本机显示设置之前先不画（只有几毫秒），免得大字模式下先闪一下标准字号
            val display by displaySettings.settings.collectAsStateWithLifecycle(initialValue = null)
            val settings = display ?: return@setContent
            QichiTheme(
                skyOverride = skyOverride,
                skyMode = settings.skyMode,
                largeText = settings.largeText,
                reduceMotion = settings.reduceMotion || systemNoAnimation,
            ) {
                QichiRoot(pendingLink = pendingLink, onLinkHandled = { pendingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.parse(intent.dataString)?.let { pendingLink = it }
        intent.sharedContent()?.let(shareInbox::offer)
    }

    // 阅读页打开了「音量键翻页」时，音量键先交给它
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = PageKeys.dispatch(event) || super.dispatchKeyEvent(event)
}
