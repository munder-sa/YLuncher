package com.gt8d.launcher.media.lyrics

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private var now = 1000L
    private fun query(title: String = "ダミー") = LyricsQuery(title, "サンプル", null, null)

    @Test fun roundTripsSyncedAndPlain() {
        val dir = File(temporaryFolder.root, "lyrics")
        val cache = LyricsCache(dir, { now })
        val synced = LyricsState.Synced(listOf(LyricLine(1000, "ダミー\n\"文\""), LyricLine(2000, "サンプル")))
        val plain = LyricsState.Plain("ダミー\nサンプル")
        cache.put(query("a"), synced)
        cache.put(query("b"), plain)
        val other = LyricsCache(dir, { now })
        assertEquals(synced, other.get(query("a")))
        assertEquals(plain, other.get(query("b")))
    }

    @Test fun evictsOldestBeyondMax() {
        val dir = temporaryFolder.newFolder()
        val cache = LyricsCache(dir, { now }, 3)
        for (i in 1..4) {
            now += 1000
            cache.put(query("$i"), LyricsState.Plain("ダミー$i"))
        }
        assertNull(cache.get(query("1")))
        for (i in 2..4) assertEquals(LyricsState.Plain("ダミー$i"), cache.get(query("$i")))
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test fun missingExpiresAfter7Days() {
        val dir = temporaryFolder.newFolder()
        val cache = LyricsCache(dir, { now })
        cache.put(query(), LyricsState.Missing)
        now += 7 * 24 * 60 * 60 * 1000L - 1
        assertEquals(LyricsState.Missing, cache.get(query()))
        assertEquals(LyricsState.Missing, LyricsCache(dir, { now }).get(query()))
        now++
        assertNull(LyricsCache(dir, { now }).get(query()))
        assertNull(cache.get(query()))
        assertFalse(File(dir, query().cacheKey + ".json").exists())
    }

    @Test fun offlineAndFailedNotStored() {
        val dir = File(temporaryFolder.root, "lyrics")
        val cache = LyricsCache(dir, { now })
        for (state in listOf(LyricsState.Offline, LyricsState.Failed, LyricsState.Off, LyricsState.Loading)) {
            cache.put(query(), state)
            assertNull(cache.get(query()))
            assertFalse(File(dir, query().cacheKey + ".json").exists())
        }
        assertFalse(dir.exists())
    }

    @Test fun corruptCacheFileIsIgnored() {
        val dir = temporaryFolder.newFolder()
        val file = File(dir, query().cacheKey + ".json")
        val cache = LyricsCache(dir, { now })
        for (body in listOf("{broken", "[]", """{"kind":"unknown","savedAt":0}""",
            """{"kind":"plain","savedAt":0,"text":42}""",
            """{"kind":"synced","savedAt":0,"lines":[["1000","ダミー"]]}""")) {
            file.writeText(body)
            assertNull(cache.get(query()))
            assertFalse(file.exists())
        }
    }

    @Test fun writeFailureDoesNotThrow() {
        val file = temporaryFolder.newFile()
        val cache = LyricsCache(file, { now })
        cache.put(query(), LyricsState.Plain("ダミー"))
        assertNull(cache.get(query()))
        assertTrue(file.isFile)
    }

    @Test fun fileWithoutVersionIsIgnored() {
        val dir = temporaryFolder.newFolder()
        val file = File(dir, query().cacheKey + ".json")
        file.writeText("""{"kind":"plain","savedAt":0,"text":"x"}""")
        assertNull(LyricsCache(dir, { now }).get(query()))
        assertFalse(file.exists())
    }

    @Test fun writtenFileHasVersion2() {
        val dir = temporaryFolder.newFolder()
        LyricsCache(dir, { now }).put(query(), LyricsState.Plain("dummy"))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            File(dir, query().cacheKey + ".json").readText()
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(2), json["v"])
    }

    @Test fun differentVersionIsIgnored() {
        val dir = temporaryFolder.newFolder()
        val file = File(dir, query().cacheKey + ".json")
        for (version in listOf("1", "3", "\"2\"", "null")) {
            file.writeText("""{"v":$version,"kind":"plain","savedAt":0,"text":"x"}""")
            assertNull(LyricsCache(dir, { now }).get(query()))
            assertFalse(file.exists())
        }
    }
}
