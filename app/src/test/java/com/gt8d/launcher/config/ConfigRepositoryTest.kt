package com.gt8d.launcher.config

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigRepositoryTest {

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

        // Case 2: Future version ("version": 2)
        val dir2 = tempFolder.newFolder("corrupt2")
        val file2 = File(dir2, "config.json")
        val futureJson = ConfigCodec.encode(Defaults.build(installed).copy(version = 2))
        file2.writeText(futureJson)

        val repo2 = ConfigRepository(file2) { installed }
        val loaded2 = repo2.config.first()
        val bak2 = File(dir2, "config.bak.json")

        assertEquals(Defaults.build(installed), loaded2)
        assertTrue(bak2.exists())
        assertEquals(futureJson, bak2.readText())
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
