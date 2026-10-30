package com.gt8d.launcher.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsNavTest {
    @Test fun start_isRoot() { assertEquals(SettingsRoute.Root, SettingsNav().current) }
    @Test fun push_thenPop_returnsToRoot() {
        val nav = SettingsNav().push(SettingsRoute.Sets)
        assertEquals(SettingsRoute.Sets, nav.current)
        assertEquals(SettingsNav(), nav.pop())
    }
    @Test fun popAtRoot_isNull() { assertNull(SettingsNav().pop()) }
}
