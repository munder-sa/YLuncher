package com.gt8d.launcher.split

import com.gt8d.launcher.config.SplitRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorBridgeTest {

    private data class BroadcastSpec(val action: String, val pkg: String, val extras: Map<String, String>)

    private class FakeActivityLauncher(
        var canResolveResult: Boolean = true,
        var startException: Throwable? = null,
        var broadcastResult: Boolean = true,
    ) : ActivityLauncher {
        val resolvedQueries = mutableListOf<Pair<String, String>>()
        val startedSpecs = mutableListOf<LaunchSpec>()
        val installed = mutableSetOf<String>()
        val broadcasts = mutableListOf<BroadcastSpec>()

        override fun canResolve(pkg: String, cls: String): Boolean {
            resolvedQueries.add(pkg to cls)
            return canResolveResult
        }

        override fun start(spec: LaunchSpec) {
            startedSpecs.add(spec)
            startException?.let { throw it }
        }

        override fun startApp(pkg: String, adjacent: Boolean): Boolean = true

        override fun isResizeable(pkg: String): Boolean = true

        override fun isInstalled(pkg: String): Boolean = pkg in installed

        override fun sendBroadcast(action: String, pkg: String, extras: Map<String, String>): Boolean {
            broadcasts.add(BroadcastSpec(action, pkg, extras))
            return broadcastResult
        }
    }

    @Test
    fun openSplit_buildsExpectedSpec() {
        val launcher = FakeActivityLauncher(canResolveResult = true)
        val bridge = VendorBridge(launcher)

        val result = bridge.openSplit("a", "b", SplitRatio.R6_4)

        assertEquals(LaunchResult.Started, result)
        assertEquals(1, launcher.startedSpecs.size)
        assertEquals(
            LaunchSpec(
                pkg = "com.sd.factoryconfig",
                cls = "com.sd.factoryconfig.SplitActivity",
                extras = mapOf(
                    "split_shortcur_package" to "a|b",
                    "split_percent" to "3:2",
                ),
            ),
            launcher.startedSpecs.single(),
        )
    }

    @Test
    fun openSplit_entryMissing_returnsEntryMissing_andDoesNotStart() {
        val launcher = FakeActivityLauncher(canResolveResult = false)
        val bridge = VendorBridge(launcher)

        val result = bridge.openSplit("a", "b", SplitRatio.R6_4)

        assertEquals(LaunchResult.Failed(FailReason.EntryMissing), result)
        assertEquals(0, launcher.startedSpecs.size)
    }

    @Test
    fun openSplit_startThrows_returnsException() {
        val launcher = FakeActivityLauncher(
            canResolveResult = true,
            startException = RuntimeException("boom"),
        )
        val bridge = VendorBridge(launcher)

        val result = bridge.openSplit("a", "b", SplitRatio.R6_4)

        assertEquals(LaunchResult.Failed(FailReason.Exception), result)
    }

    @Test
    fun carHome_installed_availableAndSendsExpectedBroadcast() {
        val launcher = FakeActivityLauncher().apply { installed.add("com.carsyso.main") }
        val bridge = VendorBridge(launcher)

        assertTrue(bridge.carHomeAvailable)
        assertTrue(bridge.switchToCarScreen())
        assertEquals(1, launcher.broadcasts.size)
        assertEquals(
            BroadcastSpec(
                action = "com.suding.main.ACTION_EXECUTE_AT_CMD",
                pkg = "com.carsyso.main",
                extras = mapOf("extra_at_cmd" to "AT#CB"),
            ),
            launcher.broadcasts.single(),
        )
    }

    @Test
    fun carHome_notInstalled_unavailableButStillSendsBroadcast() {
        val launcher = FakeActivityLauncher()
        val bridge = VendorBridge(launcher)

        assertFalse(bridge.carHomeAvailable)
        assertTrue(bridge.switchToCarScreen())
        assertEquals(1, launcher.broadcasts.size)
        assertEquals(
            BroadcastSpec(
                action = "com.suding.main.ACTION_EXECUTE_AT_CMD",
                pkg = "com.carsyso.main",
                extras = mapOf("extra_at_cmd" to "AT#CB"),
            ),
            launcher.broadcasts.single(),
        )
    }

    @Test
    fun carHome_broadcastFails_returnsFalse() {
        val launcher = FakeActivityLauncher(broadcastResult = false).apply { installed.add("com.carsyso.main") }
        val bridge = VendorBridge(launcher)

        assertTrue(bridge.carHomeAvailable)
        assertFalse(bridge.switchToCarScreen())
        assertEquals(1, launcher.broadcasts.size)
    }

    @Test
    fun vendorSplitLauncher_delegates() {
        val launcher = FakeActivityLauncher(canResolveResult = true)
        val bridge = VendorBridge(launcher)
        val splitLauncher = VendorSplitLauncher(bridge)

        val result = splitLauncher.open("x", "y", SplitRatio.R5_5)

        assertEquals(LaunchResult.Started, result)
        assertEquals(1, launcher.startedSpecs.size)
        assertEquals("1:1", launcher.startedSpecs.single().extras["split_percent"])
        assertEquals("x|y", launcher.startedSpecs.single().extras["split_shortcur_package"])
    }
}
