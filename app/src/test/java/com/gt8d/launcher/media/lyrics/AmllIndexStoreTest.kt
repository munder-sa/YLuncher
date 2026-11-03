package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmllIndexStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val raw = """{"metadata":[["musicName",["Song"]],["artists",["Artist"]]],"rawLyricFile":"one.ttml"}"""
    private val query = LyricsQuery("Song", "Artist", null, null)
    private fun transport(text: String = raw) = AmllIndexTransport { consume -> consume(200, ByteArrayInputStream(text.toByteArray())) }
    @Test fun refreshAt24Hours() = runBlocking {
        for (fails in listOf(false, true)) {
            val dir = temp.newFolder(); var now = 1000L; var calls = 0
            val t = AmllIndexTransport { consume -> calls++; if (fails) throw IOException() else consume(200, ByteArrayInputStream(raw.toByteArray())) }
            AmllIndexStore(dir, t, { now }).resolve(query, null)
            now += AmllIndexStore.DAY - 1
            val restarted = AmllIndexStore(dir, t, { now })
            restarted.resolve(query, null); assertEquals(1, calls)
            now++; restarted.resolve(query, null); assertEquals(2, calls)
        }
    }
    @Test fun concurrentResolveDownloadsOnce() = runBlocking {
        var calls = 0
        val store = AmllIndexStore(temp.newFolder(), AmllIndexTransport { consume -> calls++; delay(20); consume(200, ByteArrayInputStream(raw.toByteArray())) })
        coroutineScope { List(10) { async { store.resolve(query, null) } }.awaitAll().forEach { assertTrue(it is AmllMatch.Unique) } }
        assertEquals(1, calls)
    }
    @Test fun limitsAreInclusive() = runBlocking {
        for (size in listOf(AmllIndex.LINE_LIMIT, AmllIndex.LINE_LIMIT + 1)) {
            val line = raw + " ".repeat(size - raw.toByteArray().size)
            val result = AmllIndexStore(temp.newFolder(), transport(line)).resolve(query, null)
            assertEquals(size == AmllIndex.LINE_LIMIT, result is AmllMatch.Unique)
        }
        for (size in listOf(AmllIndex.TABLE_LIMIT, AmllIndex.TABLE_LIMIT + 1)) {
            val input = ByteArrayInputStream(ByteArray(size) { ' '.code.toByte() })
            var received = 0
            try { AmllIndex.readLines(input) { received += it.toByteArray().size }; fail("line limit") } catch (_: IllegalArgumentException) { }
            assertEquals(0, received)
            AmllIndex.checkTableSize(size.toLong().coerceAtMost(AmllIndex.TABLE_LIMIT.toLong()))
            if (size > AmllIndex.TABLE_LIMIT) try { AmllIndex.checkTableSize(size.toLong()); fail("table limit") } catch (_: IllegalArgumentException) { }
        }
        // Receive limit, with every individual line well below 64 KiB.
        for (size in listOf(AmllIndex.TABLE_LIMIT, AmllIndex.TABLE_LIMIT + 1)) {
            val bytes = ByteArray(size) { if (it % 1024 == 1023) 10 else 32 }
            var accepted = true
            try { AmllIndex.readLines(ByteArrayInputStream(bytes)) {} } catch (_: IllegalArgumentException) { accepted = false }
            assertEquals(size == AmllIndex.TABLE_LIMIT, accepted)
        }
        // NFKC expansion allows a small received index to reach the generated-table limit.
        val expanded = raw.replace("Song", "ﷺ".repeat(500))
        val encodedSize = (AmllIndex.parseRaw(expanded).encode() + "\n").toByteArray().size
        for (size in listOf(AmllIndex.TABLE_LIMIT, AmllIndex.TABLE_LIMIT + 1)) {
            val count = size / encodedSize - 1
            val remainder = size - count * encodedSize
            val baseSize = (AmllIndex.parseRaw(raw).encode() + "\n").toByteArray().size
            val finalRow = raw.replace("Song", "Song" + "a".repeat(remainder - baseSize))
            val text = (expanded + "\n").repeat(count) + finalRow
            assertTrue(text.toByteArray().size < AmllIndex.TABLE_LIMIT)
            val dir = temp.newFolder()
            val result = AmllIndexStore(dir, transport(text)).resolve(query, null)
            assertEquals(size == AmllIndex.TABLE_LIMIT, result != AmllMatch.Unavailable)
            if (size == AmllIndex.TABLE_LIMIT) assertEquals(size.toLong(), java.io.File(dir, "table.jsonl").length())
        }
    }
    @Test fun invalidLineKeepsOldTable() = runBlocking { preservesOld(raw + "\n{}") }
    @Test fun interruptedWriteKeepsOldTable() = runBlocking {
        val dir = temp.newFolder(); var now = 0L
        AmllIndexStore(dir, transport(), { now }).resolve(query, null)
        now += AmllIndexStore.DAY
        val broken = AmllIndexTransport { consume -> consume(200, object : java.io.InputStream() { override fun read(): Int = throw IOException("interrupted") }) }
        assertTrue(AmllIndexStore(dir, broken, { now }).resolve(query, null) is AmllMatch.Unique)
        assertEquals(listOf("attempt", "table.jsonl"), dir.list()!!.sorted())
        now += AmllIndexStore.DAY
        val obstacle = java.io.File(dir, "table.tmp")
        assertTrue(obstacle.mkdir())
        java.io.File(obstacle, "blocked").writeText("test")
        assertTrue(AmllIndexStore(dir, transport(), { now }).resolve(query, null) is AmllMatch.Unique)
    }
    private suspend fun preservesOld(text: String) {
        val dir = temp.newFolder(); var now = 0L
        val store = AmllIndexStore(dir, transport(), { now }); store.resolve(query, null)
        val generation = store.generation
        now += AmllIndexStore.DAY
        val restarted = AmllIndexStore(dir, transport(text), { now })
        assertTrue(restarted.resolve(query, null) is AmllMatch.Unique)
        assertEquals(generation, restarted.generation)
    }
    @Test fun staleTableStillResolves() = runBlocking { preservesOld("invalid") }
    @Test fun noTableFailureIsUnavailable() = runBlocking {
        assertEquals(AmllMatch.Unavailable, AmllIndexStore(temp.newFolder(), transport("invalid")).resolve(query, null))
    }
    @Test fun cancelledRefreshNeverPublishesAndAttemptSurvives() = runBlocking {
        val dir = temp.newFolder(); var calls = 0
        val store = AmllIndexStore(dir, AmllIndexTransport { consume ->
            calls++
            consume(200, ByteArrayInputStream(raw.toByteArray()))
            throw CancellationException("cancel")
        }, { 1000 })
        try { store.resolve(query, null); fail("cancellation") } catch (_: CancellationException) { }
        assertFalse(java.io.File(dir, "table.jsonl").exists())
        assertFalse(java.io.File(dir, "table.tmp").exists())
        assertNull(store.generation)
        val restarted = AmllIndexStore(dir, AmllIndexTransport { calls++; error("must not retry") }, { 1001 })
        assertEquals(AmllMatch.Unavailable, restarted.resolve(query, null))
        assertEquals(1, calls)
    }
}
