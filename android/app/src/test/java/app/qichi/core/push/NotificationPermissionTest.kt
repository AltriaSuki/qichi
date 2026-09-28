package app.qichi.core.push

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 安卓 13 起不问就没有通知权限：以前只在通知设置页里问，大多数人从没被问过，收不到通知。 */
class NotificationPermissionTest {
    @Test
    fun `安卓 13 及以上、没权限、没问过：要问`() {
        assertTrue(NotificationPermission.shouldAsk(sdk = 33, granted = false, alreadyAsked = false))
        assertTrue(NotificationPermission.shouldAsk(sdk = 37, granted = false, alreadyAsked = false))
    }

    @Test
    fun `已经有权限、问过了、或安卓 12 及以下：不问`() {
        assertFalse(NotificationPermission.shouldAsk(sdk = 37, granted = true, alreadyAsked = false))
        assertFalse(NotificationPermission.shouldAsk(sdk = 37, granted = false, alreadyAsked = true))
        assertFalse(NotificationPermission.shouldAsk(sdk = 32, granted = false, alreadyAsked = false))
    }
}
