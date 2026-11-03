package com.gt8d.launcher.config

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigRepositoryTest {
    @Test fun resetTurnsLyricsOff() = runTest {
        val repo = ConfigRepository(File(tempFolder.newFolder(), "config.json")) { installed }
        repo.update { it.copy(lyricsEnabled = true) }
        assertTrue(repo.config.first().lyricsEnabled)
        repo.reset()
        assertFalse(repo.config.first().lyricsEnabled)
    }
    private val v2Json = """{"version":2,"screenSets":[{"id":"s1","left":{"pkg":"a"},"right":{"type":"com.gt8d.launcher.config.Slot.Panel"}}],"homeApps":["a"],"panelApps":[],"dockApps":[],"idleApp":"a"}"""

    @Test fun migrateFromV2_writesBackupThenV3() = runTest {
        val dir = tempFolder.newFolder()
        val file = File(dir, "config.json").apply { writeText(v2Json) }
        val loaded = ConfigRepository(file) { setOf("a") }.config.first()
        assertEquals(3, loaded.version)
        assertEquals(listOf(ScreenSet("s1", Slot.App("a"), Slot.Panel)), loaded.screenSets)
        assertEquals(v2Json, File(dir, "config-v2-backup.json").readText())
        assertTrue(file.readText().contains("\"version\":3"))
        assertTrue(file.readText().contains("\"left\":{\"type\":\"com.gt8d.launcher.config.Slot.App\""))
    }

    @Test fun migrate_secondLoad_keepsBackup() = runTest {
        val dir = tempFolder.newFolder()
        val file = File(dir, "config.json").apply { writeText(v2Json) }
        val first = ConfigRepository(file) { setOf("a") }.config.first()
        val backup = File(dir, "config-v2-backup.json")
        assertEquals(v2Json, backup.readText())
        assertEquals(first, ConfigRepository(file) { setOf("a") }.config.first())
        assertEquals(v2Json, backup.readText())
    }

    @Test fun migrate_backupFails_keepsV2OnDisk() = runTest {
        val dir = tempFolder.newFolder()
        val file = File(dir, "config.json").apply { writeText(v2Json) }
        val repo = ConfigRepository(file, backupWriter = { _, _ -> throw java.io.IOException("test failure") }) { setOf("a") }
        val loaded = repo.config.first()
        assertEquals(v2Json, file.readText())
        assertEquals(3, loaded.version)
        assertEquals(listOf(ScreenSet("s1", Slot.App("a"), Slot.Panel)), loaded.screenSets)
        assertFalse(File(dir, "config-v2-backup.json").exists())
    }

    @Test fun migrate_corruptBackup_isRecreated() = runTest {
        val dir = tempFolder.newFolder()
        val file = File(dir, "config.json").apply { writeText(v2Json) }
        val backup = File(dir, "config-v2-backup.json").apply { writeText("{ broken") }
        assertEquals(3, ConfigRepository(file) { setOf("a") }.config.first().version)
        assertEquals(v2Json, backup.readText())
        assertTrue(file.readText().contains("\"version\":3"))
    }

    @Test fun migrate_v3Backup_isRecreated() = runTest {
        val dir = tempFolder.newFolder()
        val file = File(dir, "config.json").apply { writeText(v2Json) }
        val backup = File(dir, "config-v2-backup.json").apply {
            writeText(ConfigCodec.encode(Defaults.build(installed)))
        }
        assertEquals(3, ConfigRepository(file) { setOf("a") }.config.first().version)
        assertEquals(v2Json, backup.readText())
        assertTrue(file.readText().contains("\"version\":3"))
    }

    @Test
    fun backupFailure_preservesOriginalAndDoesNotEmitDefaults() = runTest {
        val dir = tempFolder.newFolder("backupFailure")
        val file = File(dir, "config.json").apply { writeText("{ broken json") }
        val original = file.readBytes()
        val backup = File(dir, "config.bak.json").apply { mkdir() }
        val child = File(backup, "keep").apply { writeText("keep backup") }
        val childBytes = child.readBytes()
        val repo = ConfigRepository(file) { installed }
        var failure: java.io.IOException? = null
        try {
            repo.config.first()
            org.junit.Assert.fail("Defaults must not be emitted")
        } catch (e: java.io.IOException) {
            failure = e
        }
        org.junit.Assert.assertNotNull(failure)
        org.junit.Assert.assertArrayEquals(original, file.readBytes())
        assertTrue(backup.isDirectory)
        assertEquals(listOf("keep"), backup.listFiles()!!.map { it.name })
        org.junit.Assert.assertArrayEquals(childBytes, child.readBytes())
    }

    @Test
    fun nextRepositoryAfterBackupFailure_readsAgain() = runTest {
        val dir = tempFolder.newFolder("readAgain")
        val file = File(dir, "config.json").apply { writeText("{ broken json") }
        val original = file.readBytes()
        val backup = File(dir, "config.bak.json").apply { mkdir() }
        File(backup, "keep").writeText("keep")
        val first = ConfigRepository(file) { installed }
        try {
            first.config.first()
            org.junit.Assert.fail("Defaults must not be emitted")
        } catch (_: java.io.IOException) {
            org.junit.Assert.assertArrayEquals(original, file.readBytes())
        }
        assertTrue(backup.deleteRecursively())
        val next = ConfigRepository(file) { installed }
        assertEquals(Defaults.build(installed), next.config.first())
        org.junit.Assert.assertArrayEquals(original, backup.readBytes())
    }

    @Test
    fun load_v1_nonDefaultSettings_persistsV2WithoutBackup() = runTest {
        val input = """{"version":1,"screenSets":[{"id":"s1","left":{"pkg":"a"},"right":{"type":"com.gt8d.launcher.config.Slot.Panel"}},{"id":"s2","left":{"pkg":"b"},"right":{"type":"com.gt8d.launcher.config.Slot.App","pkg":"c"}}],"homeApps":["c","a"],"panelApps":["b","c"],"dockApps":["c","b"],"idleApp":"b","link":{"mode":"Chrome","chromeDomains":["example.org","example.net"]},"gesture":{"stripWidthPx":12,"glow":false,"longPressMs":800}}"""
        val expected = LauncherConfig(
            version = 3,
            screenSets = listOf(
                ScreenSet("s1", Slot.App("a"), Slot.Panel, SplitRatio.R5_5, null),
                ScreenSet("s2", Slot.App("b"), Slot.App("c"), SplitRatio.R5_5, null),
            ),
            homeApps = listOf("c", "a"),
            panelApps = listOf("b", "c"),
            dockApps = listOf("c", "b"),
            idleApp = "b",
            link = LinkConfig(LinkMode.Chrome, listOf("example.org", "example.net")),
            gesture = GestureConfig(12, false, 800),
            boot = BootConfig(waitForCar = false),
        )
        val dir = tempFolder.newFolder("v1NonDefault")
        val file = File(dir, "config.json")
        file.writeText(input)
        val repo = ConfigRepository(file) { setOf("a", "b", "c") }
        assertEquals(expected, repo.config.first())
        assertFalse(File(dir, "config.bak.json").exists())
        repo.update { it }
        val reloaded = ConfigRepository(file) { setOf("a", "b", "c") }
        assertEquals(expected, reloaded.config.first())
        assertFalse(File(dir, "config.bak.json").exists())
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val installed = setOf(
        Defaults.PKG_YOUTUBE,
        Defaults.PKG_APPLE_MUSIC,
        Defaults.PKG_CHROME,
        Defaults.PKG_MAPS,
        Defaults.PKG_NEWPIPE,
    )

    @Test
    fun load_corruptFile_fallsBackAndBacksUp() = runTest {
        // Case 1: Corrupt JSON
        val dir1 = tempFolder.newFolder("corrupt1")
        val file1 = File(dir1, "config.json")
        file1.writeText("{ broken json")

        val repo1 = ConfigRepository(file1) { installed }
        val loaded1 = repo1.config.first()
        val bak1 = File(dir1, "config.bak.json")

        assertEquals(Defaults.build(installed), loaded1)
        assertTrue(bak1.exists())
        assertEquals("{ broken json", bak1.readText())

        // Case 2: Future version ("version": 3)
        val dir2 = tempFolder.newFolder("corrupt2")
        val file2 = File(dir2, "config.json")
        val futureJson = ConfigCodec.encode(Defaults.build(installed).copy(version = CONFIG_VERSION + 1))
        file2.writeText(futureJson)

        val repo2 = ConfigRepository(file2) { installed }
        val loaded2 = repo2.config.first()
        val bak2 = File(dir2, "config.bak.json")

        assertEquals(Defaults.build(installed), loaded2)
        assertTrue(bak2.exists())
        assertEquals(futureJson, bak2.readText())
    }

    @Test
    fun load_v1File_doesNotBackUp() = runTest {
        val dir = tempFolder.newFolder("v1migrate")
        val file = File(dir, "config.json")
        val v1Json = """{"version":1,"screenSets":[{"id":"s1","left":{"pkg":"a"},"right":{"type":"com.gt8d.launcher.config.Slot.Panel"}}],"homeApps":["a","b"],"panelApps":[],"dockApps":[],"idleApp":"a","link":{"mode":"Chrome","chromeDomains":[]},"gesture":{"stripWidthPx":8,"glow":true,"longPressMs":1000}}"""
        file.writeText(v1Json)

        val repo = ConfigRepository(file) { setOf("a", "b") }
        val loaded = repo.config.first()
        val bak = File(dir, "config.bak.json")

        assertEquals(3, loaded.version)
        assertEquals(listOf("a", "b"), loaded.homeApps)
        assertEquals(
            listOf(ScreenSet("s1", Slot.App("a"), Slot.Panel, SplitRatio.R5_5)),
            loaded.screenSets,
        )
        assertFalse(bak.exists())
    }

    @Test
    fun reset_restoresDefaults_keepsLastSetIndex() = runTest {
        val dir = tempFolder.newFolder("reset")
        val file = File(dir, "config.json")
        val repo = ConfigRepository(file) { installed }

        repo.lastSetIndex = 2
        repo.update { it.copy(idleApp = Defaults.PKG_YOUTUBE, gesture = GestureConfig(stripWidthPx = 20)) }
        assertEquals(Defaults.PKG_YOUTUBE, repo.config.first().idleApp)

        repo.reset()

        assertEquals(Defaults.build(installed), repo.config.first())
        assertEquals(2, repo.lastSetIndex)
    }

    @Test
    fun update_persists() = runTest {
        val dir = tempFolder.newFolder("persist")
        val file = File(dir, "config.json")
        val repo1 = ConfigRepository(file) { installed }

        repo1.lastSetIndex = 3
        repo1.update {
            it.copy(
                homeApps = listOf(Defaults.PKG_CHROME),
                link = LinkConfig(mode = LinkMode.Chrome),
            )
        }

        val repo2 = ConfigRepository(file) { installed }
        val loaded = repo2.config.first()

        assertEquals(listOf(Defaults.PKG_CHROME), loaded.homeApps)
        assertEquals(LinkMode.Chrome, loaded.link.mode)
        assertEquals(3, repo2.lastSetIndex)
    }
}
