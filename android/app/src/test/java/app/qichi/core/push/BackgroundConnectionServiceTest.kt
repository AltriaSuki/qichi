package app.qichi.core.push

import android.app.Service
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 用 startForegroundService 启动的服务，必须在几秒内调用 startForeground，哪怕马上就要停掉，
 * 否则安卓直接让 App 崩溃（ForegroundServiceDidNotStartInTimeException）。
 * 开机、装新版本后系统会重启这个服务，那时登录状态可能还没读出来。
 */
class BackgroundConnectionServiceTest {
    private fun run(loggedIn: Boolean): Pair<List<String>, Int> {
        val calls = mutableListOf<String>()
        var pending: ((Boolean) -> Unit)? = null
        val result = BackgroundConnectionService.onStart(
            foreground = { calls += "foreground" },
            whenLoaded = { decide -> calls += "wait"; pending = decide },
            connect = { calls += "connect" },
            stop = { calls += "stop" },
        )
        // 登录状态稍后才读出来
        pending!!(loggedIn)
        return calls to result
    }

    @Test
    fun `没登录：也要先挂上通知，等登录状态读出来再停`() {
        val (calls, _) = run(loggedIn = false)
        assertEquals(listOf("foreground", "wait", "stop"), calls)
    }

    @Test
    fun `登录了：挂上通知，连上服务器，被杀后重启`() {
        val (calls, result) = run(loggedIn = true)
        assertEquals(listOf("foreground", "wait", "connect"), calls)
        assertEquals(Service.START_STICKY, result)
    }
}
