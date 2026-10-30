package com.gt8d.launcher.settings

import org.junit.Assert.*
import org.junit.Test

class DailySettingsTest {
    @Test fun lists_dockLimitedTo4() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(list, DailySettings.add(list, "e", 4))
        assertEquals(list, DailySettings.add(list.dropLast(1), "d", 4))
    }
    @Test fun lists_panelLimitedTo5() {
        val list = listOf("a", "b", "c", "d", "e")
        assertEquals(list, DailySettings.add(list, "f", 5))
        assertEquals(list, DailySettings.add(list.dropLast(1), "e", 5))
    }
    @Test fun add_duplicate_noChange() { assertEquals(listOf("a"), DailySettings.add(listOf("a"), "a", null)) }
    @Test fun moveUp_first_noChange() { assertEquals(listOf("a", "b"), DailySettings.moveUp(listOf("a", "b"), "a")) }
    @Test fun remove_removes() { assertEquals(listOf("b"), DailySettings.remove(listOf("a", "b"), "a")) }
    @Test fun longPressLabel_all() {
        assertEquals(listOf("0.5秒", "0.75秒", "1秒", "1.5秒"), DailySettings.longPressChoices.map(DailySettings::longPressLabel))
    }
    @Test fun normalizeDomain_stripsSchemeAndPath() {
        assertEquals("accounts.google.com", DailySettings.normalizeDomain(" HTTPS://Accounts.Google.com/foo "))
        assertEquals("example.com", DailySettings.normalizeDomain("http://Example.com/"))
    }
    @Test fun normalizeDomain_rejectsBlankAndSpaces() {
        listOf("", "  ", "https://", "bad domain.com", "bad\tdomain.com").forEach { assertNull(DailySettings.normalizeDomain(it)) }
    }
    @Test fun moves_preserveOtherItems_andUnknownDoesNothing() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), DailySettings.moveUp(list, "b"))
        assertEquals(listOf("a", "c", "b"), DailySettings.moveDown(list, "b"))
        assertEquals(list, DailySettings.moveDown(list, "c"))
        assertEquals(list, DailySettings.moveUp(list, "missing"))
        assertEquals(list, DailySettings.moveDown(list, "missing"))
        assertEquals(list + "d", DailySettings.add(list, "d", null))
    }
}
