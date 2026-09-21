package app.qichi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.qichi.core.designsystem.QichiTheme
import app.qichi.navigation.DeepLink
import app.qichi.navigation.QichiApp
import dagger.hilt.android.AndroidEntryPoint

/** 唯一的 Activity，承载整个 App 的导航。深链从 onCreate / onNewIntent 进来。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var pendingLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingLink = DeepLink.parse(intent?.dataString)
        setContent {
            QichiTheme {
                QichiApp(pendingLink = pendingLink, onLinkHandled = { pendingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.parse(intent.dataString)?.let { pendingLink = it }
    }
}
