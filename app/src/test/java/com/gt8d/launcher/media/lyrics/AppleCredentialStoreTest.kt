package com.gt8d.launcher.media.lyrics

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppleCredentialStoreTest {
    @After fun noSecretsInLogs() {
        for (entry in ShadowLog.getLogs()) {
            assertFalse(entry.msg.orEmpty().contains(TOKEN))
            assertFalse(entry.tag.orEmpty().contains(TOKEN))
            assertFalse(entry.throwable?.stackTraceToString().orEmpty().contains(TOKEN))
        }
    }

    @Test fun storesEncryptedAndRestoresMetadata() {
        val file = MemoryFile()
        val keys = MemoryKeys()
        val store = EncryptedAppleCredentialStore(file, keys)
        assertEquals(0, file.reads + file.writes + file.deletes + keys.calls)
        for (source in AppleCredentialSource.entries) {
            for (expiry in listOf(null, 0L, Long.MAX_VALUE)) {
                val expected = credential(source = source, expiry = expiry)
                assertTrue(store.write(expected))
                val bytes = file.bytes!!
                assertArrayEquals(byteArrayOf(0x59, 0x4c, 0x41, 0x4d, 1), bytes.copyOfRange(0, 5))
                assertFalse(contains(bytes, TOKEN.toByteArray()))
                assertFalse(contains(bytes, payload(source = source.ordinal, expiry = expiry)))
                assertCredential(expected, EncryptedAppleCredentialStore(file, keys).read())
                // Verify the writer's bytes using an independent decoder and encryption contract.
                assertArrayEquals(payload(source = source.ordinal, expiry = expiry), decrypt(bytes, keys.key!!))
                assertEquals("StoredAppleCredential(redacted)", expected.toString())
                assertEquals("AppleCredentialReadResult.Ready(redacted)", store.read().toString())
            }
        }
        assertEquals(1, keys.generated)
    }

    @Test fun usesDistinctProviderGeneratedIvs() {
        val file = MemoryFile()
        val keys = MemoryKeys()
        val store = EncryptedAppleCredentialStore(file, keys)
        assertTrue(store.write(credential()))
        val first = file.bytes!!.copyOf()
        assertTrue(store.write(credential()))
        val second = file.bytes!!.copyOf()
        assertFalse(first.copyOfRange(5, 17).contentEquals(second.copyOfRange(5, 17)))
        assertFalse(first.copyOfRange(17, first.size).contentEquals(second.copyOfRange(17, second.size)))
        for (bytes in listOf(first, second)) {
            assertArrayEquals(payload(), decrypt(bytes, keys.key!!))
            file.bytes = bytes
            assertCredential(credential(), store.read())
        }
    }

    @Test fun rejectsTamperedTruncatedUnknownAndOversizedFiles() {
        val keys = MemoryKeys(newKey())
        val original = encrypt(payload(), keys.key!!)
        val file = MemoryFile()
        val store = EncryptedAppleCredentialStore(file, keys)
        for (index in original.indices) {
            val tampered = original.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            file.bytes = tampered
            assertFailed(store.read(), if (index < 5) AppleCredentialFailure.INVALID else AppleCredentialFailure.CRYPTO)
            assertArrayEquals(tampered, file.bytes)
        }
        for (size in 0 until original.size) {
            file.bytes = original.copyOf(size)
            assertTrue("truncated size $size", store.read() is AppleCredentialReadResult.Failed)
        }
        for (version in listOf(0, 2, 127, 255)) {
            file.bytes = original.copyOf().apply { this[4] = version.toByte() }
            assertFailed(store.read(), AppleCredentialFailure.INVALID)
        }
        file.bytes = original + byteArrayOf(0)
        assertFailed(store.read(), AppleCredentialFailure.CRYPTO)
        file.bytes = ByteArray(20 * 1024 + 1)
        assertFailed(store.read(), AppleCredentialFailure.INVALID)
        assertEquals(20 * 1024, file.lastReadLimit)

        val malformed = listOf(
            payload(token = byteArrayOf()),
            payload(token = "x".repeat(16 * 1024 + 1).toByteArray()),
            payload(token = byteArrayOf(0xc3.toByte(), 0x28)),
            payload(token = byteArrayOf(0xc0.toByte(), 0xaf.toByte())),
            payload(token = byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())),
            payload(token = byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte())),
            payload(token = byteArrayOf(0xe2.toByte(), 0x82.toByte())),
            payload(token = " leading".toByteArray()),
            payload(token = "trailing\u3000".toByteArray()),
            payload(token = "a\u0085b".toByteArray()),
            payload(region = byteArrayOf(0xff.toByte(), 0x70)),
            payload(region = "JP".toByteArray()),
            payload(region = "j1".toByteArray()),
            payload(source = 2), payload(source = 255),
            payload(verified = -1), payload(expiry = -1),
            payload(expiryFlag = 2), payload(expiryFlag = 255),
            payload(expiryFlag = 1), // Claims an expiry but does not contain its bytes.
            payload(expiryFlag = 0, expiry = 5),
            payload() + byteArrayOf(0),
            payload().copyOf(8),
        ) + listOf(-1, 0, Int.MAX_VALUE, 16 * 1024 + 1, TOKEN.toByteArray().size + 1).map { length ->
            payload().apply { ByteBuffer.wrap(this).putInt(length) }
        } + (0..7).map { expiryBytes -> payload(expiry = 123).copyOf(payload().size + expiryBytes) }
        for ((index, plaintext) in malformed.withIndex()) {
            // Fixtures are encoded/encrypted independently of all production codec helpers.
            val bytes = encrypt(plaintext, keys.key!!)
            file.bytes = bytes
            assertFailed(store.read(), AppleCredentialFailure.INVALID, "payload $index")
            assertArrayEquals(bytes, file.bytes)
        }
        assertEquals(0, file.writes + file.deletes + keys.generated + keys.getOrCreateCalls + keys.deletes)
    }

    @Test fun validatesInputsBeforeChangingStorage() {
        val file = MemoryFile()
        val keys = MemoryKeys()
        val store = EncryptedAppleCredentialStore(file, keys)
        assertTrue(store.write(credential()))
        val original = file.bytes!!.copyOf()
        val originalKey = keys.key
        val invalid = listOf("", " ", " leading", "trailing ", "\u3000token", "token\u3000",
            "x".repeat(16 * 1024 + 1), "é".repeat(8192) + "x", "😀".repeat(4096) + "x",
            "a\ud800b", "a\udc00b")
            .map { credential(token = it) } +
            ((0..31) + (127..159)).flatMap { code ->
                listOf("a${code.toChar()}b", "${code.toChar()}a", "a${code.toChar()}").map { credential(token = it) }
            } +
            listOf("", "j", "jpn", "JP", "j1", " jp", "jp ", "jp\n", "日本").map { credential(region = it) } +
            listOf(credential(verified = -1), credential(expiry = -1))
        for (input in invalid) {
            val calls = keys.calls
            assertFalse(store.write(input))
            assertArrayEquals(original, file.bytes)
            assertSame(originalKey, keys.key)
            assertEquals(calls, keys.calls)
            assertEquals(1, file.writes)
            assertEquals(0, file.reads + file.deletes)
        }
        for (token in listOf("x", "x".repeat(16 * 1024), "é".repeat(8192), "😀".repeat(4096), "a b")) {
            val expected = credential(token = token, verified = 0, expiry = Long.MAX_VALUE)
            assertTrue(store.write(expected))
            assertCredential(expected, store.read())
        }
        // No inferred token expiry: a nonnegative Cookie date before verification is still metadata.
        assertTrue(store.write(credential(verified = Long.MAX_VALUE, expiry = 0)))
        assertCredential(credential(verified = Long.MAX_VALUE, expiry = 0), store.read())
    }

    @Test fun handlesMissingKeysAndStorageFailuresWithoutLeaking() {
        val file = MemoryFile()
        val keys = MemoryKeys()
        val store = EncryptedAppleCredentialStore(file, keys)
        assertSame(AppleCredentialReadResult.Missing, store.read())
        assertEquals(0, keys.calls)
        assertTrue(store.write(credential()))
        val original = file.bytes!!.copyOf()
        val originalKey = keys.key!!
        keys.key = null
        assertFailed(store.read(), AppleCredentialFailure.KEY)
        assertEquals(1, keys.getOrCreateCalls)
        assertEquals(1, keys.generated)
        keys.key = newKey()
        assertFailed(store.read(), AppleCredentialFailure.CRYPTO)
        keys.key = SecretKeySpec(ByteArray(32), "HmacSHA256")
        assertFailed(store.read(), AppleCredentialFailure.CRYPTO)
        assertFalse(store.write(credential()))
        keys.key = originalKey

        for (failure in listOf(IOException(TOKEN), IllegalStateException(TOKEN))) {
            file.readFailure = failure
            assertFailed(store.read(), AppleCredentialFailure.STORAGE)
            file.readFailure = null
            file.writeFailure = failure
            assertFalse(store.write(credential()))
            file.writeFailure = null
            keys.existingFailure = failure
            assertFailed(store.read(), AppleCredentialFailure.KEY)
            keys.existingFailure = null
            keys.createFailure = failure
            assertFalse(store.write(credential()))
            keys.createFailure = null
            assertArrayEquals(original, file.bytes)
            assertSame(originalKey, keys.key)
        }
        keys.existingFailure = GeneralSecurityException(TOKEN)
        assertFailed(store.read(), AppleCredentialFailure.KEY)
        keys.existingFailure = null
        file.writeResult = false
        assertFalse(store.write(credential()))
        assertArrayEquals(original, file.bytes)
        assertCredential(credential(), store.read())
        assertFalse(contains(file.bytes!!, TOKEN.toByteArray()))

        // Key access uses the SecretKey handle, never asks the wrapper to export its bytes.
        val opaque = object : SecretKey {
            override fun getAlgorithm(): String = "AES"
            override fun getFormat(): String? = null
            override fun getEncoded(): ByteArray = throw GeneralSecurityException(TOKEN)
        }
        keys.key = opaque
        assertFailed(store.read(), AppleCredentialFailure.CRYPTO)
        assertFalse(store.write(credential()))
        assertArrayEquals(original, file.bytes)
    }

    @Test(timeout = 15_000) fun serializesWritesAndClear() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clearCalled = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val active = AtomicInteger()
        val file = object : MemoryFile() {
            override fun writeAtomically(bytes: ByteArray): Boolean = exclusive(active) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                super.writeAtomically(bytes).also { events.add("write") }
            }
            override fun delete(): Boolean = exclusive(active) {
                events.add("file clear")
                super.delete()
            }
            override fun readBounded(maxBytes: Int): ByteArray? = exclusive(active) { super.readBounded(maxBytes) }
        }
        val keys = object : MemoryKeys() {
            override fun getOrCreate(): SecretKey = exclusive(active) { super.getOrCreate() }
            override fun existing(): SecretKey? = exclusive(active) { super.existing() }
            override fun delete(): Boolean = exclusive(active) {
                events.add("key clear")
                super.delete()
            }
        }
        val store = EncryptedAppleCredentialStore(file, keys)
        val write = FutureTask(Callable { store.write(credential()) })
        val clear = FutureTask(Callable { clearCalled.countDown(); store.clear() })
        val writer = Thread(write, "credential-writer").apply { isDaemon = true }
        val clearer = Thread(clear, "credential-clearer").apply { isDaemon = true }
        try {
            writer.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            clearer.start()
            assertTrue(clearCalled.await(5, TimeUnit.SECONDS))
            awaitBlocked(clearer)
            assertFalse(clear.isDone)
            assertTrue(events.isEmpty())
            release.countDown()
            assertTrue(write.get(5, TimeUnit.SECONDS))
            assertTrue(clear.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("write", "file clear", "key clear"), events)
            assertSame(AppleCredentialReadResult.Missing, store.read())
            assertNull(file.bytes)
            assertNull(keys.key)
            assertEquals(0, active.get())
        } finally {
            release.countDown()
            writer.join(1000)
            clearer.join(1000)
        }
    }

    @Test(timeout = 15_000) fun serializesReadWithWriteAndClear() {
        for (writeNext in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val nextCalled = CountDownLatch(1)
            val file = object : MemoryFile() {
                override fun readBounded(maxBytes: Int): ByteArray? {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return super.readBounded(maxBytes)
                }
            }
            val keys = MemoryKeys(newKey())
            file.bytes = encrypt(payload(), keys.key!!)
            val store = EncryptedAppleCredentialStore(file, keys)
            val read = FutureTask(Callable { store.read() })
            val next = FutureTask(Callable {
                nextCalled.countDown()
                if (writeNext) store.write(credential(token = "synthetic-replacement")) else store.clear()
            })
            val reader = Thread(read, "credential-reader").apply { isDaemon = true }
            val mutator = Thread(next, "credential-mutator").apply { isDaemon = true }
            try {
                reader.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                mutator.start()
                assertTrue(nextCalled.await(5, TimeUnit.SECONDS))
                awaitBlocked(mutator)
                assertEquals(0, file.writes + file.deletes + keys.calls)
                release.countDown()
                assertCredential(credential(), read.get(5, TimeUnit.SECONDS))
                assertTrue(next.get(5, TimeUnit.SECONDS))
                if (writeNext) assertCredential(credential(token = "synthetic-replacement"), store.read())
                else assertSame(AppleCredentialReadResult.Missing, store.read())
            } finally {
                release.countDown()
                reader.join(1000)
                mutator.join(1000)
            }
        }
    }

    @Test fun clearAttemptsBothResourcesAndReportsFailure() {
        for (fileFails in listOf(false, true)) {
            for (keyFails in listOf(false, true)) {
                for (throws in listOf(false, true)) {
                    val file = MemoryFile()
                    val keys = MemoryKeys()
                    val store = EncryptedAppleCredentialStore(file, keys)
                    assertTrue(store.write(credential()))
                    file.deleteResult = !fileFails
                    keys.deleteResult = !keyFails
                    if (throws && fileFails) file.deleteFailure = IOException(TOKEN)
                    if (throws && keyFails) keys.deleteFailure = GeneralSecurityException(TOKEN)
                    assertEquals(!fileFails && !keyFails, store.clear())
                    assertEquals(1, file.deletes)
                    assertEquals(1, keys.deletes)
                    assertEquals(fileFails, file.bytes != null)
                    assertEquals(keyFails, keys.key != null)
                    if (fileFails && !keyFails) assertFailed(store.read(), AppleCredentialFailure.KEY)
                    if (!fileFails) assertSame(AppleCredentialReadResult.Missing, store.read())
                }
            }
        }
        assertTrue(EncryptedAppleCredentialStore(MemoryFile(), MemoryKeys()).clear())
    }

    @Test fun doesNotSwallowErrors() {
        val file = MemoryFile()
        val keys = MemoryKeys()
        val store = EncryptedAppleCredentialStore(file, keys)
        assertTrue(store.write(credential()))
        file.readFailure = AssertionError("synthetic failure")
        assertThrows(AssertionError::class.java) { store.read() }
        file.readFailure = null
        keys.existingFailure = AssertionError("synthetic failure")
        assertThrows(AssertionError::class.java) { store.read() }
        keys.existingFailure = null
        keys.createFailure = AssertionError("synthetic failure")
        assertThrows(AssertionError::class.java) { store.write(credential()) }
        keys.createFailure = null
        file.writeFailure = AssertionError("synthetic failure")
        assertThrows(AssertionError::class.java) { store.write(credential()) }
        file.deleteFailure = AssertionError("synthetic failure")
        assertThrows(AssertionError::class.java) { store.clear() }
        file.deleteFailure = null
        keys.deleteFailure = AssertionError("synthetic failure")
        assertThrows(AssertionError::class.java) { store.clear() }
    }

    private open class MemoryFile : AppleCredentialFile {
        var bytes: ByteArray? = null
        var readFailure: Throwable? = null
        var writeFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var writeResult = true
        var deleteResult = true
        var reads = 0
        var writes = 0
        var deletes = 0
        var lastReadLimit = -1
        override fun readBounded(maxBytes: Int): ByteArray? {
            reads++
            lastReadLimit = maxBytes
            readFailure?.let { throw it }
            val value = bytes ?: return null
            if (value.size > maxBytes) throw AppleCredentialFileTooLarge()
            return value.copyOf()
        }
        override fun writeAtomically(bytes: ByteArray): Boolean {
            writes++
            writeFailure?.let { throw it }
            if (!writeResult) return false
            this.bytes = bytes.copyOf()
            return true
        }
        override fun delete(): Boolean {
            deletes++
            deleteFailure?.let { throw it }
            if (!deleteResult) return false
            bytes = null
            return true
        }
    }

    private open class MemoryKeys(var key: SecretKey? = null) : AppleCredentialKeys {
        var existingFailure: Throwable? = null
        var createFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var deleteResult = true
        var existingCalls = 0
        var getOrCreateCalls = 0
        var generated = 0
        var deletes = 0
        val calls: Int get() = existingCalls + getOrCreateCalls + deletes
        override fun existing(): SecretKey? {
            existingCalls++
            existingFailure?.let { throw it }
            return key
        }
        override fun getOrCreate(): SecretKey {
            getOrCreateCalls++
            createFailure?.let { throw it }
            return key ?: newKey().also { key = it; generated++ }
        }
        override fun delete(): Boolean {
            deletes++
            deleteFailure?.let { throw it }
            if (!deleteResult) return false
            key = null
            return true
        }
    }

    private fun assertCredential(expected: StoredAppleCredential, result: AppleCredentialReadResult) {
        assertTrue(result is AppleCredentialReadResult.Ready)
        val actual = (result as AppleCredentialReadResult.Ready).credential
        assertEquals(expected.token, actual.token)
        assertEquals(expected.storefront, actual.storefront)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.verifiedAtEpochMillis, actual.verifiedAtEpochMillis)
        assertEquals(expected.cookieExpiresAtEpochMillis, actual.cookieExpiresAtEpochMillis)
    }

    private fun assertFailed(result: AppleCredentialReadResult, reason: AppleCredentialFailure, label: String = "") {
        assertTrue(label, result is AppleCredentialReadResult.Failed)
        assertEquals(label, reason, (result as AppleCredentialReadResult.Failed).reason)
        assertEquals("Failed(reason=$reason)", result.toString())
    }

    private fun contains(bytes: ByteArray, sequence: ByteArray): Boolean =
        (0..bytes.size - sequence.size).any { offset -> sequence.indices.all { bytes[offset + it] == sequence[it] } }

    private fun <T> exclusive(active: AtomicInteger, action: () -> T): T {
        assertEquals(1, active.incrementAndGet())
        return try { action() } finally { active.decrementAndGet() }
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (thread.state != Thread.State.BLOCKED && thread.isAlive && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    private fun credential(
        token: String = TOKEN,
        region: String = "jp",
        source: AppleCredentialSource = AppleCredentialSource.WEB,
        verified: Long = VERIFIED,
        expiry: Long? = null,
    ) = StoredAppleCredential(token, region, source, verified, expiry)

    private fun payload(
        token: ByteArray = TOKEN.toByteArray(),
        region: ByteArray = "jp".toByteArray(),
        source: Int = 1,
        verified: Long = VERIFIED,
        expiry: Long? = null,
        expiryFlag: Int = if (expiry == null) 0 else 1,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use {
            it.writeInt(token.size)
            it.write(token)
            it.write(region)
            it.writeByte(source)
            it.writeLong(verified)
            it.writeByte(expiryFlag)
            if (expiry != null) it.writeLong(expiry)
        }
    }.toByteArray()

    private fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val header = byteArrayOf(0x59, 0x4c, 0x41, 0x4d, 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        assertEquals(12, cipher.iv.size)
        assertEquals(128, cipher.parameters.getParameterSpec(GCMParameterSpec::class.java).tLen)
        cipher.updateAAD(header)
        return header + cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(bytes: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(5, 17)))
        cipher.updateAAD(bytes.copyOfRange(0, 5))
        return cipher.doFinal(bytes.copyOfRange(17, bytes.size))
    }

    private companion object {
        const val TOKEN = "synthetic-apple-store-secret-+%=="
        const val VERIFIED = 1_700_000_000_123L
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
