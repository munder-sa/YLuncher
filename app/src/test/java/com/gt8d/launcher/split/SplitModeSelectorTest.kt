package com.gt8d.launcher.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SplitModeSelectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeSplitModeStore(
        var current: SplitModeState = SplitModeState(),
    ) : SplitModeStore {
        var saveCount: Int = 0
        override fun load(): SplitModeState = current
        override fun save(state: SplitModeState) {
            current = state
            saveCount++
        }
    }

    @Test
    fun threeCountableFailures_disableVendor() {
        val store = FakeSplitModeStore()
        val selector = SplitModeSelector(store, dReady = { false })

        selector.onFailure(FailReason.Timeout, countable = true)
        assertEquals(SplitMode.Vendor, selector.current())
        assertEquals(1, selector.state.consecutiveFailures)
        assertEquals(FailReason.Timeout, selector.state.lastFailure)

        selector.onFailure(FailReason.Timeout, countable = true)
        assertEquals(SplitMode.Vendor, selector.current())
        assertEquals(2, selector.state.consecutiveFailures)

        selector.onFailure(FailReason.Timeout, countable = true)
        assertEquals(SplitMode.LeftOnly, selector.current())
        assertFalse(selector.state.vendorUsable)
        assertEquals(3, selector.state.consecutiveFailures)
        assertEquals(store.current, selector.state)
    }

    @Test
    fun uncountableFailures_ignored() {
        val store = FakeSplitModeStore()
        val selector = SplitModeSelector(store, dReady = { false })

        repeat(5) {
            selector.onFailure(FailReason.Timeout, countable = false)
        }

        assertEquals(SplitMode.Vendor, selector.current())
        assertTrue(selector.state.vendorUsable)
        assertEquals(0, selector.state.consecutiveFailures)
        assertEquals(FailReason.Timeout, selector.state.lastFailure)
        assertEquals(store.current, selector.state)
    }

    @Test
    fun success_resetsCount() {
        val store = FakeSplitModeStore()
        val selector = SplitModeSelector(store, dReady = { false })

        selector.onFailure(FailReason.Timeout, countable = true)
        selector.onFailure(FailReason.Exception, countable = true)
        assertEquals(2, selector.state.consecutiveFailures)

        selector.onSuccess()
        assertEquals(0, selector.state.consecutiveFailures)
        assertEquals(SplitMode.Vendor, selector.current())

        selector.onFailure(FailReason.Timeout, countable = true)
        selector.onFailure(FailReason.Timeout, countable = true)
        assertEquals(SplitMode.Vendor, selector.current())
        assertTrue(selector.state.vendorUsable)
        assertEquals(2, selector.state.consecutiveFailures)
    }

    @Test
    fun entryMissing_switchesToLeftOnly_whenDNotReady() {
        val store = FakeSplitModeStore()
        val selector = SplitModeSelector(store, dReady = { false })

        selector.onFailure(FailReason.EntryMissing, countable = false)

        assertFalse(selector.state.vendorUsable)
        assertEquals(FailReason.EntryMissing, selector.state.lastFailure)
        assertEquals(SplitMode.LeftOnly, selector.current())
        assertEquals(store.current, selector.state)
    }

    @Test
    fun entryMissing_switchesToAppPair_whenDReady() {
        val store = FakeSplitModeStore()
        val selector = SplitModeSelector(store, dReady = { true })

        selector.onFailure(FailReason.EntryMissing, countable = true)

        assertFalse(selector.state.vendorUsable)
        assertEquals(FailReason.EntryMissing, selector.state.lastFailure)
        assertEquals(SplitMode.AppPair, selector.current())
        assertEquals(store.current, selector.state)
    }

    @Test
    fun onBoot_entryFound_restoresVendor() {
        val store = FakeSplitModeStore(
            SplitModeState(
                vendorUsable = false,
                consecutiveFailures = 2,
                lastFailure = FailReason.Timeout,
            ),
        )
        val selector = SplitModeSelector(store, dReady = { false })
        assertEquals(SplitMode.LeftOnly, selector.current())

        selector.onBoot(entryFound = true)

        assertEquals(SplitMode.Vendor, selector.current())
        assertTrue(selector.state.vendorUsable)
        assertEquals(0, selector.state.consecutiveFailures)
        assertEquals(store.current, selector.state)
    }

    @Test
    fun onBoot_entryMissing_disablesVendor() {
        val store = FakeSplitModeStore()
        val selector = SplitModeSelector(store, dReady = { false })
        assertEquals(SplitMode.Vendor, selector.current())

        selector.onBoot(entryFound = false)

        assertFalse(selector.state.vendorUsable)
        assertEquals(SplitMode.LeftOnly, selector.current())
        assertEquals(store.current, selector.state)
    }

    @Test
    fun restoreVendor_manual() {
        val store = FakeSplitModeStore(
            SplitModeState(
                vendorUsable = false,
                consecutiveFailures = 3,
                lastFailure = FailReason.Timeout,
            ),
        )
        val selector = SplitModeSelector(store, dReady = { true })
        assertEquals(SplitMode.AppPair, selector.current())

        selector.restoreVendor()

        assertEquals(SplitMode.Vendor, selector.current())
        assertTrue(selector.state.vendorUsable)
        assertEquals(0, selector.state.consecutiveFailures)
        assertEquals(store.current, selector.state)
    }

    @Test
    fun state_persistsAcrossInstances() {
        val file = tempFolder.newFile("split_mode.json")
        file.delete()

        val first = SplitModeSelector(FileSplitModeStore(file), dReady = { false })
        assertEquals(SplitMode.Vendor, first.current())

        first.onFailure(FailReason.EntryMissing, countable = true)
        assertEquals(SplitMode.LeftOnly, first.current())

        val second = SplitModeSelector(FileSplitModeStore(file), dReady = { false })
        assertEquals(SplitMode.LeftOnly, second.current())
        assertFalse(second.state.vendorUsable)
        assertEquals(FailReason.EntryMissing, second.state.lastFailure)
    }

    @Test
    fun fileStore_corrupt_returnsDefault() {
        val file = tempFolder.newFile("corrupt.json")
        file.writeText("{ not valid json ")

        val store = FileSplitModeStore(file)
        assertEquals(SplitModeState(), store.load())
    }
}
