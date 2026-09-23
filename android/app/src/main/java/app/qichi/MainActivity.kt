package app.qichi

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import app.qichi.core.reading.ReaderFragments
import app.qichi.core.data.DisplaySettingsStore
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sky
import app.qichi.navigation.DeepLink
import app.qichi.navigation.QichiRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 唯一的 Activity，承载整个 App 的导航。深链从 onCreate / onNewIntent 进来。是 FragmentActivity，因为阅读页用了 Readium 的 Fragment。 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var displaySettings: DisplaySettingsStore

    private var pendingLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 阅读页（Readium）是 Fragment：恢复时要用能造出它的工厂，必须在 super.onCreate 之前装上
        supportFragmentManager.fragmentFactory = ReaderFragments
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) ReaderFragments.dropRestored(supportFragmentManager)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingLink = DeepLink.parse(intent?.dataString)
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
            QichiTheme(skyOverride = skyOverride, largeText = settings.largeText, reduceMotion = settings.reduceMotion) {
                QichiRoot(pendingLink = pendingLink, onLinkHandled = { pendingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.parse(intent.dataString)?.let { pendingLink = it }
    }
}
