package com.gt8d.launcher.media.lyrics

import java.io.File
import java.io.FileFilter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachePruningCostTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val query = LyricsQuery("new", "artist", null, null)
    private val request = LyricsRequest(query)
    private val missing = AmllResult(LyricsState.Missing, AmllReason.NOT_FOUND, cacheable = true)
    private val amllName = AmllCache.key(request) + ".json"
    private val lyricsName = query.cacheKey + ".json"

    @Test fun amllAt300SkipsSavedAtReadsAndIgnoresNonEntries() {
        val dir = temporaryFolder.newFolder()
        val reads = SavedAtReads()
        val cache = AmllCache(dir, { 10_000L }).also { it.onPruneSavedAtRead = reads }
        val names = seed(dir, 298) + entry(dir, "broken.json", "{").name + amllName
        val ignored = ignoredEntries(dir)

        cache.put(request, null, missing)

        assertEquals(300, names.size)
        assertEquals(names, jsonNames(dir))
        reads.assertNone()
        ignored.forEach { assertTrue(it.exists()) }
    }

    @Test fun amllReadsOnceAndPreservesCorruptMinimumAndNameTieBreak() {
        val dir = TrackingDirectory(temporaryFolder.newFolder())
        val reads = SavedAtReads()
        val cache = AmllCache(dir, { 10_000L }).also { it.onPruneSavedAtRead = reads }
        val names = seed(dir, 298) + setOf(
            entry(dir, "a-broken.json", "{").name,
            entry(dir, "b-broken.json", "[]").name,
            entry(dir, "c-minimum.json", Long.MIN_VALUE).name,
            entry(dir, "y-tie.json", 0L).name,
            entry(dir, "z-tie.json", 0L).name,
            amllName,
        )
        val ignored = ignoredEntries(dir)

        cache.put(request, null, missing)

        val deleted = listOf("a-broken.json", "b-broken.json", "c-minimum.json", "y-tie.json")
        assertEquals(deleted, dir.deletions)
        assertEquals(names - deleted.toSet(), jsonNames(dir))
        assertEquals(300, jsonNames(dir).size)
        reads.assertOnce(names)
        ignored.forEach { assertTrue(it.exists()) }
        assertEquals(missing, cache.get(request, null))
    }

    @Test fun amllFailedDeletionDoesNotReduceCountAndContinues() {
        val dir = TrackingDirectory(temporaryFolder.newFolder(), setOf("blocked.json"))
        val reads = SavedAtReads()
        val cache = AmllCache(dir, { 10_000L }).also { it.onPruneSavedAtRead = reads }
        val names = seed(dir, 298) + setOf(
            entry(dir, "blocked.json", 0L).name,
            entry(dir, "next.json", 1L).name,
            amllName,
        )

        cache.put(request, null, missing)

        assertEquals(listOf("blocked.json", "next.json"), dir.deletions)
        assertEquals(names - "next.json", jsonNames(dir))
        assertEquals(300, jsonNames(dir).size)
        reads.assertOnce(names)
    }

    @Test fun amllAt32MiBSkipsReadsAndOneByteOverContinuesAfterFailedDeletion() {
        val dir = TrackingDirectory(temporaryFolder.newFolder(), setOf("large-0.json"))
        val cache = AmllCache(dir, { 10_000L })
        cache.put(request, null, missing)
        val newEntrySize = File(dir, amllName).length().toInt()
        val mib = 1024 * 1024
        val totalLimit = 32L * mib
        val largeFiles = (0 until 32).map { index ->
            val size = if (index == 31) mib - newEntrySize else mib
            val body = """{"savedAt":$index}"""
            entry(dir, "large-$index.json", body + " ".repeat(size - body.length))
        }
        val names = jsonNames(dir)
        val reads = SavedAtReads()
        cache.onPruneSavedAtRead = reads
        assertEquals(totalLimit, jsonFiles(dir).sumOf { it.length() })

        cache.put(request, null, missing)

        assertEquals(names, jsonNames(dir))
        assertEquals(totalLimit, jsonFiles(dir).sumOf { it.length() })
        assertTrue(dir.deletions.isEmpty())
        reads.assertNone()

        largeFiles.last().appendText(" ")
        assertEquals(totalLimit + 1, jsonFiles(dir).sumOf { it.length() })
        cache.put(request, null, missing)

        assertEquals(listOf("large-0.json", "large-1.json"), dir.deletions)
        assertEquals(names - "large-1.json", jsonNames(dir))
        assertEquals(totalLimit + 1 - mib, jsonFiles(dir).sumOf { it.length() })
        reads.assertOnce(names)
    }

    @Test fun lyricsAt300SkipsSavedAtReadsAndIgnoresNonEntries() {
        val dir = temporaryFolder.newFolder()
        val reads = SavedAtReads()
        val cache = LyricsCache(dir, { 10_000L }).also { it.onPruneSavedAtRead = reads }
        val names = seed(dir, 298) + entry(dir, "broken.json", "{").name + lyricsName
        val ignored = ignoredEntries(dir)

        cache.put(query, LyricsState.Missing)

        assertEquals(300, names.size)
        assertEquals(names, jsonNames(dir))
        reads.assertNone()
        ignored.forEach { assertTrue(it.exists()) }
    }

    @Test fun lyricsAbove300ReadsEachFileOnceAndDeletesOldest() {
        val dir = TrackingDirectory(temporaryFolder.newFolder())
        val reads = SavedAtReads()
        val cache = LyricsCache(dir, { 10_000L }).also { it.onPruneSavedAtRead = reads }
        val names = seed(dir, 298) + setOf(
            entry(dir, "z-oldest.json", 0L).name,
            entry(dir, "a-next.json", 1L).name,
            lyricsName,
        )
        val ignored = ignoredEntries(dir)

        cache.put(query, LyricsState.Missing)

        assertEquals(listOf("z-oldest.json"), dir.deletions)
        assertEquals(names - "z-oldest.json", jsonNames(dir))
        assertEquals(300, jsonNames(dir).size)
        reads.assertOnce(names)
        ignored.forEach { assertTrue(it.exists()) }
        assertEquals(LyricsState.Missing, cache.get(query))
    }

    @Test fun lyricsEqualSavedAtPreservesDirectoryEnumerationOrder() {
        val dir = TrackingDirectory(temporaryFolder.newFolder())
        val reads = SavedAtReads()
        val cache = LyricsCache(dir, { 10_000L }, 3).also { it.onPruneSavedAtRead = reads }
        val names = setOf(
            entry(dir, "a-tie.json", 0L).name,
            entry(dir, "b-tie.json", 0L).name,
            entry(dir, "z-tie.json", 0L).name,
            lyricsName,
        )
        assertEquals(listOf("z-tie.json", "b-tie.json", "a-tie.json"), jsonFiles(dir).map { it.name })

        cache.put(query, LyricsState.Missing)

        assertEquals(listOf("z-tie.json"), dir.deletions)
        assertEquals(names - "z-tie.json", jsonNames(dir))
        reads.assertOnce(names)
    }

    @Test fun lyricsCorruptJsonUsesLastModifiedAndPreservesDeletionOrder() {
        val dir = TrackingDirectory(temporaryFolder.newFolder())
        val reads = SavedAtReads()
        val cache = LyricsCache(dir, { 10_000L }, 2).also { it.onPruneSavedAtRead = reads }
        val broken = entry(dir, "a-broken.json", "{")
        val badTimestamp = entry(dir, "b-bad-timestamp.json", """{"savedAt":"bad"}""")
        assertTrue(broken.setLastModified(2_000L))
        assertTrue(badTimestamp.setLastModified(4_000L))
        assertEquals(2_000L, broken.lastModified())
        assertEquals(4_000L, badTimestamp.lastModified())
        val names = setOf(
            entry(dir, "z-oldest.json", 1_000L).name,
            entry(dir, "m-next.json", 3_000L).name,
            broken.name,
            badTimestamp.name,
            lyricsName,
        )

        cache.put(query, LyricsState.Missing)

        val deleted = listOf("z-oldest.json", "a-broken.json", "m-next.json")
        assertEquals(deleted, dir.deletions)
        assertEquals(names - deleted.toSet(), jsonNames(dir))
        reads.assertOnce(names)
    }

    @Test fun lyricsFailedDeletionKeepsExistingAttemptLimit() {
        val dir = TrackingDirectory(temporaryFolder.newFolder(), setOf("blocked.json"))
        val reads = SavedAtReads()
        val cache = LyricsCache(dir, { 10_000L }, 2).also { it.onPruneSavedAtRead = reads }
        val names = setOf(
            entry(dir, "blocked.json", 0L).name,
            entry(dir, "next.json", 1L).name,
            lyricsName,
        )

        cache.put(query, LyricsState.Missing)

        assertEquals(listOf("blocked.json"), dir.deletions)
        assertEquals(names, jsonNames(dir))
        reads.assertOnce(names)
    }

    private fun entry(dir: File, name: String, body: String): File =
        File(dir, name).also { it.writeText(body, Charsets.UTF_8) }

    private fun entry(dir: File, name: String, savedAt: Long): File =
        entry(dir, name, """{"savedAt":$savedAt}""")

    private fun seed(dir: File, count: Int): Set<String> = (0 until count).map { index ->
        entry(dir, "kept-${index.toString().padStart(3, '0')}.json", 100L + index).name
    }.toSet()

    private fun ignoredEntries(dir: File): List<File> = listOf(
        entry(dir, "unfinished.tmp", "{"),
        File(dir, "directory.json").also { assertTrue(it.mkdir()) },
    )

    private fun jsonFiles(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }!!.toList()

    private fun jsonNames(dir: File): Set<String> = jsonFiles(dir).map { it.name }.toSet()

    private class SavedAtReads : (File) -> Unit {
        private val counts = mutableMapOf<String, Int>()

        override fun invoke(file: File) {
            counts[file.name] = counts.getOrDefault(file.name, 0) + 1
        }

        fun assertNone() = assertEquals(emptyMap<String, Int>(), counts)
        fun assertOnce(names: Set<String>) = assertEquals(names.associateWith { 1 }, counts)
    }

    // Fix enumeration order and deletion failures without filesystem permission assumptions.
    private class TrackingDirectory(dir: File, private val failedDeletes: Set<String> = emptySet()) : File(dir.path) {
        val deletions = mutableListOf<String>()

        override fun listFiles(filter: FileFilter?): Array<File>? = super.listFiles(filter)
            ?.sortedByDescending { it.name }
            ?.map { file ->
                object : File(file.path) {
                    override fun delete(): Boolean {
                        deletions += name
                        return name !in failedDeletes && super.delete()
                    }
                }
            }?.toTypedArray()
    }
}
