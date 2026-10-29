package com.gt8d.launcher.config

import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.edge.EdgeStripService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppGraphConfigTest {
    @get:Rule val temp = TemporaryFolder()
    private var previousGesture = EdgeStripService.gestureConfig
    private val cfg = Defaults.build(emptySet()).copy(gesture = GestureConfig(12, false, 800))

    @Before fun before() {
        previousGesture = EdgeStripService.gestureConfig
        AppGraph.resetConfigStateForTest()
        DiagLog.clearForTest()
    }

    @After fun after() {
        AppGraph.resetConfigStateForTest()
        EdgeStripService.gestureConfig = previousGesture
        DiagLog.clearForTest()
    }

    private fun failureLogs() = DiagLog.dump().filter { "[Config] load failed:" in it }

    private fun assertInitialFailure() {
        assertNull(AppGraph.configSnapshotForTest)
        assertTrue(AppGraph.configError.value)
        assertEquals(1, failureLogs().size)
        assertEquals(previousGesture, EdgeStripService.gestureConfig)
    }

    @Test fun initialReadIoFailure_setsConfigErrorWithoutEscaping() = runTest {
        val result = runCatching { AppGraph.collectConfig { flow { throw IOException("read failed") } } }
        assertNull("collectConfig must return normally", result.exceptionOrNull())
        assertInitialFailure()
    }

    @Test fun sourceCreationIoFailure_setsConfigErrorWithoutEscaping() = runTest {
        val result = runCatching { AppGraph.collectConfig { throw IOException("create failed") } }
        assertNull("collectConfig must return normally", result.exceptionOrNull())
        assertInitialFailure()
    }

    @Test fun backupFailure_setsConfigErrorAndPreservesOriginal() = runTest {
        val dir = temp.newFolder("backup")
        val file = File(dir, "config.json").apply { writeText("{ broken json") }
        val original = file.readBytes()
        val backup = File(dir, "config.bak.json").apply { mkdir() }
        val child = File(backup, "keep").apply { writeText("keep") }
        val repo = ConfigRepository(file) { emptySet() }
        val result = runCatching { AppGraph.collectConfig { repo.config } }
        assertNull("collectConfig must return normally", result.exceptionOrNull())
        assertInitialFailure()
        assertArrayEquals(original, file.readBytes())
        assertTrue(backup.isDirectory)
        assertEquals("keep", child.readText())
        assertEquals(listOf("keep"), backup.listFiles()!!.map { it.name })
    }

    @Test fun successfulLoad_publishesConfigAndGesture() = runTest {
        AppGraph.collectConfig { flowOf(cfg) }
        assertEquals(cfg, AppGraph.configSnapshotForTest)
        assertEquals(cfg.gesture, EdgeStripService.gestureConfig)
        assertFalse(AppGraph.configError.value)
        assertTrue(failureLogs().isEmpty())
    }

    @Test fun cancellation_isRethrownWithoutConfigError() = runTest {
        val expected = CancellationException("cancel")
        val result = runCatching { AppGraph.collectConfig { flow { throw expected } } }
        assertSame(expected, result.exceptionOrNull())
        assertFalse(AppGraph.configError.value)
        assertTrue(failureLogs().isEmpty())
    }

    @Test fun readFailure_doesNotRetry() = runTest {
        var sources = 0
        var collections = 0
        val result = runCatching {
            AppGraph.collectConfig {
                sources++
                flow { collections++; throw IOException("read failed") }
            }
        }
        assertNull("collectConfig must return normally", result.exceptionOrNull())
        assertEquals(1, sources)
        assertEquals(1, collections)
        assertInitialFailure()
    }

    @Test fun ioFailureAfterSuccess_keepsLastGoodConfig() = runTest {
        val result = runCatching {
            AppGraph.collectConfig { flow { emit(cfg); throw IOException("later") } }
        }
        assertNull("collectConfig must return normally", result.exceptionOrNull())
        assertEquals(cfg, AppGraph.configSnapshotForTest)
        assertEquals(cfg.gesture, EdgeStripService.gestureConfig)
        assertTrue(AppGraph.configError.value)
        assertEquals(1, failureLogs().size)
    }
}
