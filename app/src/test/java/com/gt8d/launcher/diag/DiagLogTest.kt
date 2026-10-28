package com.gt8d.launcher.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagLogTest {
    @Before
    fun setUp() {
        DiagLog.clearForTest()
    }

    @Test
    fun diagLog_keepsLast500() {
        for (i in 1..501) {
            DiagLog.d("Test", "line-$i")
        }
        val lines = DiagLog.dump()
        assertEquals(500, lines.size)
        assertTrue("Expected first line to end with '[Test] line-2' but was: ${lines.first()}", lines.first().endsWith("[Test] line-2"))
        assertTrue("Expected last line to end with '[Test] line-501' but was: ${lines.last()}", lines.last().endsWith("[Test] line-501"))
    }

    @Test
    fun diagLog_worksWithoutAndroidLog() {
        DiagLog.d("Tag", "hello")
        val lines = DiagLog.dump()
        assertEquals(1, lines.size)
        assertTrue(lines.single().endsWith("[Tag] hello"))
    }
}
