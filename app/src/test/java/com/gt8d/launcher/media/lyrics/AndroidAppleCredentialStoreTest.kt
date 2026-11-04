package com.gt8d.launcher.media.lyrics

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidAppleCredentialStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun noSecretsInAndroidLogs() {
        for (entry in ShadowLog.getLogs()) {
            assertFalse(entry.msg.orEmpty().contains(TOKEN))
            assertFalse(entry.tag.orEmpty().contains(TOKEN))
            assertFalse(entry.throwable?.stackTraceToString().orEmpty().contains(TOKEN))
        }
    }

    @Test fun storesOnlyInNoBackupDirectory() {
        val context = context("directories")
        val config = File(context.filesDir, "config.json").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("{\"fixture\":true}")
        }
        val preferences = File(context.root, "shared_prefs/settings.xml").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("<map />")
        }
        val before = snapshot(context.root)
        val keys = TestKeys()
        val store = AndroidAppleCredentialStore(context, keys)
        AndroidAppleCredentialStore(context) // Even the real keystore adapter must be lazy.
        assertEquals(0, context.noBackupAccesses)
        assertEquals(0, keys.calls)
        assertEquals(before, snapshot(context.root))
        assertSame(AppleCredentialReadResult.Missing, store.read())
        assertFalse(File(context.root, "no_backup").exists())
        assertEquals(0, keys.calls)

        assertTrue(store.write(credential()))
        val base = baseFile(context)
        assertTrue(base.isFile)
        val saved = base.readBytes()
        assertFalse(String(saved, Charsets.ISO_8859_1).contains(TOKEN))
        assertEquals(setOf("no_backup/apple-music/credentials.v1"), snapshot(context.root).keys - before.keys)
        assertEquals(before, snapshot(context.root).filterKeys { it in before })
        assertEquals("{\"fixture\":true}", config.readText())
        assertEquals("<map />", preferences.readText())
        assertCredential(AndroidAppleCredentialStore(context, keys).read())
        assertEquals(1, keys.generated)
        assertEquals(1, keys.getOrCreateCalls)
        assertTrue(store.clear())
        assertEquals(before, snapshot(context.root))
    }

    @Test fun atomicFilesAreBoundedAndRemovedOnClear() {
        val context = context("atomic")
        val keys = TestKeys()
        val store = AndroidAppleCredentialStore(context, keys)
        val backend = AndroidAppleCredentialFile { context.noBackupFilesDir }
        assertTrue(store.write(credential()))
        val base = baseFile(context)
        val fresh = File(base.path + ".new")
        val backup = File(base.path + ".bak")
        val committed = base.readBytes()
        assertArrayEquals(committed, backend.readBounded(20 * 1024))
        assertFalse(fresh.exists())
        assertFalse(backup.exists())
        assertCredential(store.read())

        // AtomicFile's documented startWrite/finishWrite/failWrite lifecycle and external locking:
        // https://developer.android.com/reference/android/util/AtomicFile
        // Test rollback and platform companion handling on the actual Robolectric Android backend.
        val atomic = AtomicFile(base)
        val pending = atomic.startWrite()
        pending.write(byteArrayOf(8, 9, 10))
        atomic.failWrite(pending)
        assertArrayEquals(committed, backend.readBounded(20 * 1024))
        assertFalse(fresh.exists())
        backup.writeBytes(committed)
        base.writeBytes(byteArrayOf(1, 2, 3))
        fresh.writeBytes(byteArrayOf(4, 5, 6))
        assertCredential(store.read())
        assertArrayEquals(committed, base.readBytes())
        assertFalse(backup.exists())
        assertFalse(fresh.exists())

        val boundary = ByteArray(20 * 1024) { (it % 251).toByte() }
        base.writeBytes(boundary)
        assertArrayEquals(boundary, backend.readBounded(20 * 1024))
        val lookups = keys.existingCalls
        base.writeBytes(boundary + byteArrayOf(1))
        assertThrows(AppleCredentialFileTooLarge::class.java) { backend.readBounded(20 * 1024) }
        assertFailed(store.read(), AppleCredentialFailure.INVALID)
        assertEquals(lookups, keys.existingCalls)
        assertEquals(20 * 1024 + 1L, base.length())
        assertFalse(backend.writeAtomically(boundary + byteArrayOf(2)))
        assertArrayEquals(boundary + byteArrayOf(1), base.readBytes())
        // An explicit, authenticated replacement can recover from the oversized file.
        assertTrue(store.write(credential()))
        assertCredential(store.read())

        val unrelated = File(base.parentFile, "unrelated.bin").apply { writeBytes(byteArrayOf(71, 72)) }
        backup.writeBytes(committed)
        fresh.writeBytes(byteArrayOf(3, 4))
        assertTrue(store.clear())
        assertFalse(base.exists())
        assertFalse(backup.exists())
        assertFalse(fresh.exists())
        assertNull(keys.key)
        assertArrayEquals(byteArrayOf(71, 72), unrelated.readBytes())
        assertSame(AppleCredentialReadResult.Missing, store.read())

        // A nonempty directory cannot be read or removed as the credential file. No chmod/root
        // assumptions are needed, and files inside it must never be recursively erased.
        assertTrue(base.mkdir())
        val child = File(base, "keep.bin").apply { writeBytes(byteArrayOf(11)) }
        assertFailed(store.read(), AppleCredentialFailure.STORAGE)
        assertFalse(store.write(credential())) // finishWrite cannot replace this directory.
        assertTrue(child.isFile)
        val deletes = keys.deletes
        assertFalse(store.clear())
        assertEquals(deletes + 1, keys.deletes)
        assertNull(keys.key)
        assertFailed(store.read(), AppleCredentialFailure.STORAGE)
        assertTrue(child.delete())
        assertTrue(store.clear())
        assertSame(AppleCredentialReadResult.Missing, store.read())

        // startWrite failure leaves an existing committed credential intact.
        assertTrue(store.write(credential()))
        val previous = base.readBytes()
        assertTrue(fresh.mkdir())
        val pendingChild = File(fresh, "keep.bin").apply { writeBytes(byteArrayOf(22)) }
        assertFalse(store.write(credential()))
        assertArrayEquals(previous, base.readBytes())
        assertFalse(store.clear())
        assertFalse(base.exists())
        assertTrue(pendingChild.isFile)
        assertNull(keys.key)
        assertFailed(store.read(), AppleCredentialFailure.STORAGE)
        assertTrue(pendingChild.delete())
        assertTrue(store.clear())

        // The legacy backup is also part of deletion, including failure reporting.
        assertTrue(store.write(credential()))
        assertTrue(backup.mkdir())
        val backupChild = File(backup, "keep.bin").apply { writeBytes(byteArrayOf(33)) }
        assertFalse(store.clear())
        assertFalse(base.exists())
        assertTrue(backupChild.isFile)
        assertNull(keys.key)
        assertTrue(backupChild.delete())
        assertTrue(store.clear())

        assertTrue(store.write(credential()))
        keys.deleteFailure = IOException(TOKEN)
        assertFalse(store.clear())
        assertFalse(base.exists())
        assertFalse(fresh.exists())
        assertFalse(backup.exists())
        assertNotNull(keys.key)
        assertSame(AppleCredentialReadResult.Missing, store.read())
        keys.deleteFailure = null
        assertTrue(store.clear())
        assertNull(keys.key)
        assertArrayEquals(byteArrayOf(71, 72), unrelated.readBytes())
    }

    @Test fun androidKeyParametersMatchContract() {
        val spec = appleCredentialKeySpec()
        assertEquals("yluncher.apple_music.credentials.v1", spec.keystoreAlias)
        assertEquals(256, spec.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertArrayEquals(arrayOf("GCM"), spec.blockModes)
        assertArrayEquals(arrayOf("NoPadding"), spec.encryptionPaddings)
        assertTrue(spec.isRandomizedEncryptionRequired)
        assertFalse(spec.isUserAuthenticationRequired)
        assertFalse(spec.isStrongBoxBacked)
        // This inspects the Android request, not GT8D's actual keystore/hardware implementation.
    }

    private fun context(name: String) = StorageContext(temporary.newFolder(name))

    private class StorageContext(val root: File) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var noBackupAccesses = 0
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File {
            noBackupAccesses++
            return File(root, "no_backup")
        }
        override fun getFilesDir(): File = File(root, "files")
    }

    private class TestKeys : AppleCredentialKeys {
        var key: SecretKey? = null
        var generated = 0
        var existingCalls = 0
        var getOrCreateCalls = 0
        var deletes = 0
        var deleteFailure: Exception? = null
        val calls: Int get() = existingCalls + getOrCreateCalls + deletes
        override fun existing(): SecretKey? {
            existingCalls++
            return key
        }
        override fun getOrCreate(): SecretKey {
            getOrCreateCalls++
            return key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
                key = it
                generated++
            }
        }
        override fun delete(): Boolean {
            deletes++
            deleteFailure?.let { throw it }
            key = null
            return true
        }
    }

    private fun baseFile(context: StorageContext) = File(context.root, "no_backup/apple-music/credentials.v1")

    private fun snapshot(root: File): Map<String, List<Byte>> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes().toList() }

    private fun credential() = StoredAppleCredential(TOKEN, "jp", AppleCredentialSource.WEB, 1234, 5678)

    private fun assertCredential(result: AppleCredentialReadResult) {
        assertTrue(result is AppleCredentialReadResult.Ready)
        val value = (result as AppleCredentialReadResult.Ready).credential
        assertEquals(TOKEN, value.token)
        assertEquals("jp", value.storefront)
        assertEquals(AppleCredentialSource.WEB, value.source)
        assertEquals(1234L, value.verifiedAtEpochMillis)
        assertEquals(5678L, value.cookieExpiresAtEpochMillis)
    }

    private fun assertFailed(result: AppleCredentialReadResult, reason: AppleCredentialFailure) {
        assertTrue(result is AppleCredentialReadResult.Failed)
        assertEquals(reason, (result as AppleCredentialReadResult.Failed).reason)
        assertFalse(result.toString().contains(TOKEN))
    }

    private companion object {
        const val TOKEN = "synthetic-android-store-secret-+%=="
    }
}
