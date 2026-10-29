package com.gt8d.launcher.link

import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingUrlTest {
    private val clock = FakeScheduler()
    private val pending = PendingUrl(clock)

    @Test fun pending_takeWithinValid_returnsAndClears() {
        pending.set("url")
        clock.advanceBy(14999)
        assertEquals("url", pending.take())
        assertNull(pending.take())
    }
    @Test fun pending_expired_returnsNull() {
        pending.set("url")
        clock.advanceBy(15001)
        assertNull(pending.take())
        assertNull(pending.take())
    }
    @Test fun pending_empty_returnsNull() { assertNull(pending.take()) }
    @Test fun pending_setTwice_keepsLatest() {
        pending.set("a")
        pending.set("b")
        assertEquals("b", pending.take())
    }
    @Test fun pending_exactlyValid_returns() {
        pending.set("url")
        clock.advanceBy(15000)
        assertEquals("url", pending.take())
    }
    @Test fun pending_resetRefreshesExpiry() {
        pending.set("a")
        clock.advanceBy(10000)
        pending.set("b")
        clock.advanceBy(10000)
        assertEquals("b", pending.take())
    }
}
