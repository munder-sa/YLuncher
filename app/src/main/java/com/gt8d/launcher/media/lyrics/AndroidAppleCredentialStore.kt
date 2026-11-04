package com.gt8d.launcher.media.lyrics

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidAppleCredentialStore internal constructor(
    context: Context,
    keys: AppleCredentialKeys,
) : AppleCredentialStore {
    constructor(context: Context) : this(context, AndroidAppleCredentialKeys())

    private val applicationContext = context.applicationContext ?: context
    private val delegate = EncryptedAppleCredentialStore(
        // Even accessing noBackupFilesDir can create a directory, so defer that access.
        AndroidAppleCredentialFile { applicationContext.noBackupFilesDir },
        keys,
    )

    override fun read(): AppleCredentialReadResult = delegate.read()
    override fun write(credential: StoredAppleCredential): Boolean = delegate.write(credential)
    override fun clear(): Boolean = delegate.clear()
}

internal const val APPLE_CREDENTIAL_KEY_ALIAS = "yluncher.apple_music.credentials.v1"

internal fun appleCredentialKeySpec(): KeyGenParameterSpec =
    KeyGenParameterSpec.Builder(
        APPLE_CREDENTIAL_KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(false)
        .build()

private class AndroidAppleCredentialKeys : AppleCredentialKeys {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    override fun existing(): SecretKey? {
        val key = keyStore.getKey(APPLE_CREDENTIAL_KEY_ALIAS, null) ?: return null
        return key as? SecretKey ?: throw KeyStoreException("Invalid Apple credential key type")
    }

    override fun getOrCreate(): SecretKey = existing() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply { init(appleCredentialKeySpec()) }
        .generateKey()

    override fun delete(): Boolean {
        keyStore.deleteEntry(APPLE_CREDENTIAL_KEY_ALIAS)
        return !keyStore.containsAlias(APPLE_CREDENTIAL_KEY_ALIAS)
    }
}

/** Used only under EncryptedAppleCredentialStore's per-instance lock. */
internal class AndroidAppleCredentialFile(directory: () -> File) : AppleCredentialFile {
    private val base by lazy { File(directory(), "apple-music/credentials.v1") }
    private val atomic by lazy { AtomicFile(base) }
    private val companions: List<File>
        get() = listOf(base, File(base.path + ".new"), File(base.path + ".bak"))

    override fun readBounded(maxBytes: Int): ByteArray? {
        require(maxBytes in 0..APPLE_CREDENTIAL_MAX_FILE_BYTES)
        val input = try {
            atomic.openRead()
        } catch (failure: FileNotFoundException) {
            // Do not turn an unreadable existing file (or an interrupted write) into Missing.
            if (companions.any(::exists)) throw failure
            return null
        }
        return input.use {
            val bytes = ByteArray(maxBytes + 1)
            var count = 0
            while (true) {
                val read = it.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
                if (count > maxBytes) throw AppleCredentialFileTooLarge()
            }
            bytes.copyOf(count)
        }
    }

    override fun writeAtomically(bytes: ByteArray): Boolean {
        if (bytes.size > APPLE_CREDENTIAL_MAX_FILE_BYTES) return false
        var stream: FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            stream.write(bytes)
            // finishWrite may report some failures only via Android logging. Require sync ourselves,
            // then check the actual committed bytes instead of treating its void return as success.
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
            readBounded(APPLE_CREDENTIAL_MAX_FILE_BYTES)?.contentEquals(bytes) == true
        } catch (_: Exception) {
            if (stream != null) {
                try { atomic.failWrite(stream) } catch (_: Exception) { /* Report failure below. */ }
            }
            false
        }
    }

    override fun delete(): Boolean {
        atomic.delete()
        return companions.none(::exists)
    }

    private fun exists(file: File): Boolean = try {
        // Unlike File.exists(), permission/I/O errors must not look like absence.
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        true
    } catch (_: NoSuchFileException) {
        false
    }
}
