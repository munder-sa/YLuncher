package com.gt8d.launcher.config

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LastSetIndexStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test fun firstReadIsLazy_repeatedGetsReadOnce() {
        val io = MemoryIo(3)
        val store = LastSetIndexStore(io)
        assertEquals(0, io.reads)
        repeat(100) { assertEquals(3, store.lastSetIndex) }
        assertEquals(1, io.reads)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun samePersistedValueSet100Times_doesNotWrite_evenBeforeGet() {
        for (value in listOf(0, 3)) {
            val io = MemoryIo(value)
            val store = LastSetIndexStore(io)
            repeat(100) { store.lastSetIndex = value }
            assertEquals(value, store.lastSetIndex)
            assertEquals(1, io.reads)
            assertTrue(io.writes.isEmpty())
        }
    }

    @Test fun sameSuccessfullyWrittenValueSet100Times_doesNotWriteAgain() {
        val io = MemoryIo(null)
        val store = LastSetIndexStore(io)
        store.lastSetIndex = 2
        repeat(100) { store.lastSetIndex = 2 }
        assertEquals(listOf(2), io.writes)
        assertEquals(2, store.lastSetIndex)
        assertEquals(2, io.persisted)
        assertEquals(1, io.reads)
    }

    @Test fun changedValues_arePersistedSynchronouslyInOrder() {
        val io = MemoryIo(0)
        val store = LastSetIndexStore(io)
        val events = mutableListOf<String>()
        io.beforeWrite = { value -> events.add("write $value cached ${store.lastSetIndex}") }
        for (value in listOf(2, 4, 0)) {
            store.lastSetIndex = value
            assertEquals(value, io.persisted)
            events.add("returned ${store.lastSetIndex}")
        }
        assertEquals(listOf(2, 4, 0), io.writes)
        assertEquals(listOf(
            "write 2 cached 0", "returned 2",
            "write 4 cached 2", "returned 4",
            "write 0 cached 4", "returned 0",
        ), events)
        assertEquals(1, io.reads)
    }

    @Test fun missingValue_cachesDefaultButStillPersistsZero() {
        val io = MemoryIo(null)
        val store = LastSetIndexStore(io)
        repeat(100) { assertEquals(0, store.lastSetIndex) }
        assertEquals(1, io.reads)
        assertTrue(io.writes.isEmpty())
        repeat(100) { store.lastSetIndex = 0 }
        assertEquals(listOf(0), io.writes)
        assertEquals(0, io.persisted)
        assertEquals(1, io.reads)
    }

    @Test fun failedRead_cachesDefaultButStillPersistsZero() {
        for (failure in listOf(NumberFormatException("invalid"), IOException("unreadable"))) {
            val io = MemoryIo(null).apply { readFailure = failure }
            val store = LastSetIndexStore(io)
            repeat(100) { assertEquals(0, store.lastSetIndex) }
            assertEquals(1, io.reads)
            repeat(100) { store.lastSetIndex = 0 }
            assertEquals(listOf(0), io.writes)
            assertEquals(0, store.lastSetIndex)
            assertEquals(0, io.persisted)
            assertEquals(1, io.reads)
        }
    }

    @Test fun missingFile_zeroSetCreatesParentAndFile() {
        val file = File(tempFolder.newFolder(), "nested/last_set_index.txt")
        val store = LastSetIndexStore(file)
        assertEquals(0, store.lastSetIndex)
        assertFalse(file.exists())
        store.lastSetIndex = 0
        assertEquals("0", file.readText())
        assertEquals(0, LastSetIndexStore(file).lastSetIndex)
    }

    @Test fun invalidFile_zeroSetRepairsFile() {
        for (text in listOf("", " \n ", "invalid", "2147483648", "1 2")) {
            val file = tempFolder.newFile().apply { writeText(text) }
            val store = LastSetIndexStore(file)
            repeat(100) { assertEquals(0, store.lastSetIndex) }
            store.lastSetIndex = 0
            assertEquals("0", file.readText())
            assertEquals(0, LastSetIndexStore(file).lastSetIndex)
        }
    }

    @Test fun persistedZero_isNotRewritten() {
        val text = " \n0\t "
        val file = tempFolder.newFile().apply { writeText(text) }
        val store = LastSetIndexStore(file)
        repeat(100) { store.lastSetIndex = 0 }
        assertEquals(0, store.lastSetIndex)
        assertEquals(text, file.readText())
    }

    @Test fun fileFormat_keepsTrimmedIntegerReadsAndPlainIntegerWrites() {
        val file = tempFolder.newFile().apply { writeText(" \n+3\t ") }
        val store = LastSetIndexStore(file)
        assertEquals(3, store.lastSetIndex)
        for (value in listOf(Int.MIN_VALUE, Int.MAX_VALUE, 0)) {
            store.lastSetIndex = value
            assertEquals(value.toString(), file.readText())
            assertEquals(value, LastSetIndexStore(file).lastSetIndex)
        }
    }

    @Test fun repository_readsLazilyCachesAndReloadsWithNewInstance() {
        val dir = tempFolder.newFolder()
        val configFile = File(dir, "config.json")
        val indexFile = File(dir, "last_set_index.txt").apply { writeText("1") }
        val first = ConfigRepository(configFile) { emptySet() }
        indexFile.writeText("2")
        assertEquals(2, first.lastSetIndex)
        indexFile.writeText("3")
        repeat(100) { assertEquals(2, first.lastSetIndex) }
        val next = ConfigRepository(configFile) { emptySet() }
        assertEquals(3, next.lastSetIndex)
        next.lastSetIndex = 4
        assertEquals("4", indexFile.readText())
        assertEquals(4, next.lastSetIndex)
        assertEquals(4, ConfigRepository(configFile) { emptySet() }.lastSetIndex)
    }

    @Test fun failedWrite_propagatesKeepsCacheAndRetriesSameValue() {
        for (initial in listOf(null, 2)) {
            val io = MemoryIo(initial)
            val store = LastSetIndexStore(io)
            val target = if (initial == null) 0 else 3
            val failure = IOException("write failed")
            io.beforeWrite = { throw failure }
            assertSame(failure, assertThrows(IOException::class.java) { store.lastSetIndex = target })
            assertEquals(initial ?: 0, store.lastSetIndex)
            assertEquals(initial, io.persisted)
            assertEquals(listOf(target), io.writes)
            io.beforeWrite = {}
            store.lastSetIndex = target
            repeat(100) { store.lastSetIndex = target }
            assertEquals(listOf(target, target), io.writes)
            assertEquals(target, store.lastSetIndex)
            assertEquals(target, io.persisted)
            assertEquals(1, io.reads)
        }
    }

    @Test fun partialWriteFailure_doesNotSkipPreviouslyPersistedValue() {
        val io = MemoryIo(2)
        val store = LastSetIndexStore(io)
        val failure = IOException("failed after truncation")
        io.beforeWrite = {
            io.persisted = null
            throw failure
        }
        assertSame(failure, assertThrows(IOException::class.java) { store.lastSetIndex = 3 })
        assertEquals(2, store.lastSetIndex)
        io.beforeWrite = {}
        store.lastSetIndex = 2
        assertEquals(listOf(3, 2), io.writes)
        assertEquals(2, io.persisted)
        assertEquals(2, store.lastSetIndex)
    }

    @Test(timeout = 15_000) fun concurrentFirstGets_shareOneRead() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val io = object : MemoryIo(3) {
            override fun read(): Int? {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return super.read()
            }
        }
        val store = LastSetIndexStore(io)
        val first = FutureTask(Callable { store.lastSetIndex })
        val second = FutureTask(Callable { store.lastSetIndex })
        val reader = daemonThread(first)
        val nextReader = daemonThread(second)
        try {
            reader.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            nextReader.start()
            awaitBlocked(nextReader)
            release.countDown()
            assertEquals(3, first.get(5, TimeUnit.SECONDS))
            assertEquals(3, second.get(5, TimeUnit.SECONDS))
            assertEquals(1, io.reads)
        } finally {
            release.countDown()
            reader.join(1000)
            nextReader.join(1000)
        }
    }

    @Test(timeout = 15_000) fun concurrentWrites_finishInOrderAndKeepLatestValue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val io = object : MemoryIo(0) {
            override fun write(value: Int) {
                events.add("start $value")
                if (value == 1) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                super.write(value)
                events.add("saved $value")
            }
        }
        val store = LastSetIndexStore(io)
        val first = FutureTask(Callable { store.lastSetIndex = 1 })
        val second = FutureTask(Callable { store.lastSetIndex = 2 })
        val writer = daemonThread(first)
        val nextWriter = daemonThread(second)
        try {
            writer.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            nextWriter.start()
            awaitBlocked(nextWriter)
            assertFalse(first.isDone)
            assertFalse(second.isDone)
            assertEquals(listOf("start 1"), events)
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("start 1", "saved 1", "start 2", "saved 2"), events)
            assertEquals(listOf(1, 2), io.writes)
            assertEquals(2, io.persisted)
            assertEquals(2, store.lastSetIndex)
            assertEquals(1, io.reads)
        } finally {
            release.countDown()
            writer.join(1000)
            nextWriter.join(1000)
        }
    }

    @Test(timeout = 15_000) fun concurrentGet_waitsForWriteAndSeesSavedValue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val io = MemoryIo(0).apply {
            beforeWrite = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        }
        val store = LastSetIndexStore(io)
        assertEquals(0, store.lastSetIndex)
        val write = FutureTask(Callable { store.lastSetIndex = 1 })
        val read = FutureTask(Callable { store.lastSetIndex })
        val writer = daemonThread(write)
        val reader = daemonThread(read)
        try {
            writer.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            reader.start()
            awaitBlocked(reader)
            assertFalse(write.isDone)
            assertFalse(read.isDone)
            release.countDown()
            write.get(5, TimeUnit.SECONDS)
            assertEquals(1, read.get(5, TimeUnit.SECONDS))
            assertEquals(1, io.persisted)
            assertEquals(1, io.reads)
        } finally {
            release.countDown()
            writer.join(1000)
            reader.join(1000)
        }
    }

    private open class MemoryIo(var persisted: Int?) : LastSetIndexIo {
        var reads = 0
        val writes = mutableListOf<Int>()
        var readFailure: Exception? = null
        var beforeWrite: (Int) -> Unit = {}

        override fun read(): Int? {
            reads++
            readFailure?.let { throw it }
            return persisted
        }

        override fun write(value: Int) {
            writes.add(value)
            beforeWrite(value)
            persisted = value
        }
    }

    private fun daemonThread(task: Runnable) = Thread(task).apply { isDaemon = true }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (thread.state != Thread.State.BLOCKED && thread.isAlive && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }
}
