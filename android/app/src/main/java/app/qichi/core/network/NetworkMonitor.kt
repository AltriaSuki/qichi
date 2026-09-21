package app.qichi.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 手机当前是否联网：聊天标题旁的「离线」、离线时置灰附件按钮、联网恢复时唤起发件箱。 */
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(currentlyOnline())
    override val isOnline: StateFlow<Boolean> = state.asStateFlow()

    init {
        connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                state.value = true
            }

            // 默认网络丢了就是离线；换到别的网络时会紧接着收到 onAvailable。
            // （此时再查 activeNetwork 可能还拿到旧网络，不能用它判断）
            override fun onLost(network: Network) {
                state.value = false
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        })
    }

    private fun currentlyOnline(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** 测试用。 */
class FakeNetworkMonitor(online: Boolean = true) : NetworkMonitor {
    override val isOnline = MutableStateFlow(online)
}
