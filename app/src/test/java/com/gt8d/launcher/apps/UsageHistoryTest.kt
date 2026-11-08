package com.gt8d.launcher.apps

import android.app.Application
import android.app.AppOpsManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageHistoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun permission(mode: Int) {
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, mode)
    }
    @Test fun ownHomeActivityBecomesHomeMarker() {
        assertEquals(UsageLaunch(UsageHistory.HOME_MARKER, 1234), usageLaunchOf(
            app.packageName, "com.gt8d.launcher.ui.home.HomeActivity", 1234, app.packageName))
    }
    @Test fun ownPanelActivityKeepsPackageName() {
        assertEquals(UsageLaunch(app.packageName, 1234), usageLaunchOf(
            app.packageName, "com.gt8d.launcher.ui.panel.PanelActivity", 1234, app.packageName))
    }
    @Test fun otherAppKeepsPackageNameEvenWithHomeClassName() {
        assertEquals(UsageLaunch("other.app", 1234), usageLaunchOf(
            "other.app", "com.gt8d.launcher.ui.home.HomeActivity", 1234, app.packageName))
    }
    @Test fun nullPackageProducesNoLaunch() {
        assertNull(usageLaunchOf(null, "com.gt8d.launcher.ui.home.HomeActivity", 1234, app.packageName))
    }
    @Test fun nullClassNameKeepsPackageName() {
        assertEquals(UsageLaunch(app.packageName, 1234), usageLaunchOf(app.packageName, null, 1234, app.packageName))
    }
    @Test fun unavailableReturnsNullWithoutQuery() {
        permission(AppOpsManager.MODE_IGNORED)
        var queried = false
        val history = UsageHistory(app) { _, _ -> queried = true; emptyList() }
        assertFalse(history.available())
        assertNull(history.launchesSince(0))
        assertFalse(queried)
    }
    @Test fun queryFailureReturnsNull() {
        permission(AppOpsManager.MODE_ALLOWED)
        val history = UsageHistory(app) { _, _ -> error("query failed") }
        assertTrue(history.available())
        assertNull(history.launchesSince(0))
    }
    @Test fun periodAndStableTimeOrdering() {
        permission(AppOpsManager.MODE_ALLOWED)
        val before = System.currentTimeMillis()
        val history = UsageHistory(app) { begin, end ->
            assertEquals(100L, begin)
            assertTrue(end >= before && end <= System.currentTimeMillis())
            listOf(UsageLaunch("later", 200), UsageLaunch("same1", 100), UsageLaunch("same2", 100),
                UsageLaunch("old", 99), UsageLaunch("future", end + 1))
        }
        assertEquals(listOf(UsageLaunch("same1", 100), UsageLaunch("same2", 100), UsageLaunch("later", 200)),
            history.launchesSince(100))
    }
}
