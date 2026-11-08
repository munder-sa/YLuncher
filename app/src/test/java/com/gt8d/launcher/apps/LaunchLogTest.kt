package com.gt8d.launcher.apps

import android.app.Application
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class LaunchLogTest {
    @Test fun savedEntriesSurviveNewLogWithEmptyHistory() {
        val io = FakeIo()
        val expected = listOf(UsageLaunch("a", 100), UsageLaunch("b", 200))
        assertEquals(expected, LaunchLog(io).merge(expected, 100))
        assertEquals("100\ta\n200\tb", io.text)

        assertEquals(expected, LaunchLog(io).merge(emptyList(), 100))
        assertEquals(2, io.reads)
        assertEquals(1, io.writes)
    }

    @Test fun mergeDeduplicatesPackageAndTimestamp() {
        val io = FakeIo("100\ta\n100\ta")
        val log = LaunchLog(io)
        val observed = listOf(UsageLaunch("a", 100), UsageLaunch("a", 200), UsageLaunch("b", 100))
        val expected = listOf(UsageLaunch("a", 100), UsageLaunch("b", 100), UsageLaunch("a", 200))

        assertEquals(expected, log.merge(observed + observed, 0))
        assertEquals(expected, log.merge(observed, 0))
        assertEquals("100\ta\n100\tb\n200\ta", io.text)
        assertEquals(1, io.writes)
    }

    @Test fun pruningRemovesOldEntriesFromStorageAndKeepsFutureTime() {
        val io = FakeIo("99\told\n100\tboundary\n${Long.MAX_VALUE}\tfuture")
        val expected = listOf(UsageLaunch("boundary", 100), UsageLaunch("future", Long.MAX_VALUE))

        assertEquals(expected, LaunchLog(io).merge(listOf(UsageLaunch("also.old", 98)), 100))
        assertEquals("100\tboundary\n${Long.MAX_VALUE}\tfuture", io.text)
        assertEquals(expected, LaunchLog(io).merge(emptyList(), 100))
        assertEquals(1, io.writes)
    }

    @Test fun entryLimitKeepsNewestFromStoredAndObservedEntries() {
        val io = FakeIo("100\ta\n400\td\n200\tb")
        val log = LaunchLog(io, maxEntries = 3)
        val expected = listOf(UsageLaunch("c", 300), UsageLaunch("d", 400), UsageLaunch("e", 500))

        assertEquals(expected, log.merge(listOf(UsageLaunch("e", 500), UsageLaunch("c", 300)), 0))
        assertEquals("300\tc\n400\td\n500\te", io.text)
        assertEquals(expected, LaunchLog(io).merge(emptyList(), 0))
    }

    @Test fun equalTimestampLimitKeepsPackageOrderDeterministic() {
        val io = FakeIo()
        val log = LaunchLog(io, maxEntries = 2)

        assertEquals(listOf(UsageLaunch("b", 100), UsageLaunch("c", 100)), log.merge(
            listOf(UsageLaunch("c", 100), UsageLaunch("a", 100), UsageLaunch("b", 100)), 0))
        assertEquals("100\tb\n100\tc", io.text)
    }

    @Test fun malformedLinesAreSkippedWhileValidLinesAreRead() {
        val io = FakeIo("bad\tinvalid\nmissing-tab\n200\t\n\tinvalid\n9223372036854775808\toverflow\n300\tb\n100\ta\n")

        assertEquals(listOf(UsageLaunch("a", 100), UsageLaunch("b", 300)),
            LaunchLog(io).merge(emptyList(), 0))
        assertEquals(1, io.reads)
        assertEquals(0, io.writes)
    }

    @Test fun readFailureDoesNotWriteAndRetriesNextMerge() {
        val io = FakeIo("150\tsaved").apply { readFailure = IOException("unreadable") }
        val log = LaunchLog(io)
        val observed = listOf(UsageLaunch("later", 300), UsageLaunch("z", 100),
            UsageLaunch("old", 99), UsageLaunch("a", 100))
        val expectedObserved = listOf(UsageLaunch("a", 100), UsageLaunch("z", 100), UsageLaunch("later", 300))

        assertEquals(expectedObserved, log.merge(observed, 100))
        assertEquals(1, io.reads)
        assertEquals(0, io.writes)
        assertEquals("150\tsaved", io.text)

        io.readFailure = null
        assertEquals(listOf(UsageLaunch("a", 100), UsageLaunch("z", 100),
            UsageLaunch("saved", 150), UsageLaunch("later", 300)), log.merge(observed, 100))
        assertEquals(2, io.reads)
        assertEquals(1, io.writes)
        assertEquals("100\ta\n100\tz\n150\tsaved\n300\tlater", io.text)
    }

    @Test fun unchangedContentDoesNotWriteAgainOrReread() {
        val io = FakeIo()
        val log = LaunchLog(io)
        assertEquals(emptyList<UsageLaunch>(), log.merge(emptyList(), 0))
        assertEquals(0, io.writes)
        val expected = listOf(UsageLaunch("a", 100), UsageLaunch("b", 200))
        assertEquals(expected, log.merge(expected, 0))
        io.readFailure = IOException("must use memory")

        assertEquals(expected, log.merge(expected.reversed(), 100))
        assertEquals(expected, log.merge(emptyList(), 100))
        assertEquals(1, io.reads)
        assertEquals(1, io.writes)
    }

    @Test fun writeFailureKeepsMergedEntriesAndRetriesOnNextChange() {
        val io = FakeIo("100\tsaved").apply { writeFailure = IOException("full") }
        val log = LaunchLog(io)
        val expected = listOf(UsageLaunch("saved", 100), UsageLaunch("unsaved", 200))

        assertEquals(expected, log.merge(listOf(UsageLaunch("unsaved", 200)), 0))
        assertEquals(1, io.writes)
        assertEquals("100\tsaved", io.text)
        io.writeFailure = null
        assertEquals(expected, log.merge(emptyList(), 0))
        assertEquals(1, io.writes)

        val changed = expected + UsageLaunch("next", 300)
        assertEquals(changed, log.merge(listOf(UsageLaunch("next", 300)), 0))
        assertEquals(2, io.writes)
        assertEquals("100\tsaved\n200\tunsaved\n300\tnext", io.text)
        assertEquals(changed, LaunchLog(io).merge(emptyList(), 0))
    }

    @Test fun zeroLimitClearsStoredEntries() {
        val io = FakeIo("100\ta")

        assertEquals(emptyList<UsageLaunch>(),
            LaunchLog(io, maxEntries = 0).merge(listOf(UsageLaunch("b", 200)), 0))
        assertEquals("", io.text)
        assertEquals(emptyList<UsageLaunch>(), LaunchLog(io).merge(emptyList(), 0))
    }

    @Test fun negativeLimitIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { LaunchLog(FakeIo(), maxEntries = -1) }
    }

    private class FakeIo(var text: String? = null) : LaunchLogIo {
        var reads = 0
        var writes = 0
        var readFailure: Exception? = null
        var writeFailure: Exception? = null

        override fun read(): String? {
            reads++
            readFailure?.let { throw it }
            return text
        }

        override fun write(text: String) {
            writes++
            writeFailure?.let { throw it }
            this.text = text
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LaunchLogFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingFileReturnsNull() {
        assertNull(LaunchLogFile(File(temporary.root, "missing.tsv")).read())
    }

    @Test fun writtenTextCanBeReadByAnotherFileInstance() {
        val file = File(temporary.root, "launch_log.tsv")
        val text = "100\tcom.example.a\n200\tcom.example.b"
        LaunchLogFile(file).write(text)

        assertEquals(text, LaunchLogFile(file).read())
        assertEquals(listOf(UsageLaunch("com.example.a", 100), UsageLaunch("com.example.b", 200)),
            LaunchLog(file).merge(emptyList(), 0))
    }

    @Test fun interruptedWritePreservesPreviousContent() {
        val file = File(temporary.root, "launch_log.tsv")
        LaunchLogFile(file).write("100\tsaved")
        AtomicFile(file).startWrite().use { it.write("200\tpartial".toByteArray(Charsets.UTF_8)) }

        assertEquals("100\tsaved", LaunchLogFile(file).read())
    }

    @Test fun backupIsRecoveredWhenBaseFileIsMissing() {
        val file = File(temporary.root, "launch_log.tsv")
        File(file.path + ".bak").writeText("100\tsaved", Charsets.UTF_8)

        assertEquals("100\tsaved", LaunchLogFile(file).read())
        assertEquals("100\tsaved", file.readText(Charsets.UTF_8))
    }

    @Test fun unreadablePathThrowsInsteadOfReturningNull() {
        val directory = temporary.newFolder("not-a-file")

        assertThrows(IOException::class.java) { LaunchLogFile(directory).read() }
    }
}
