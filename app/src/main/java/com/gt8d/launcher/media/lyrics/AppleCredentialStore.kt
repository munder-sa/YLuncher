package com.gt8d.launcher.media.lyrics

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AppleCredentialSource { MANUAL, WEB }

class StoredAppleCredential(
    val token: String,
    val storefront: String,
    val source: AppleCredentialSource,
    val verifiedAtEpochMillis: Long,
    // Cookie metadata only; this is not the token's validity deadline.
    val cookieExpiresAtEpochMillis: Long? = null,
) {
    override fun toString(): String = "StoredAppleCredential(redacted)"
}

enum class AppleCredentialFailure { INVALID, STORAGE, KEY, CRYPTO }

sealed interface AppleCredentialReadResult {
    data object Missing : AppleCredentialReadResult
    class Ready(val credential: StoredAppleCredential) : AppleCredentialReadResult {
        override fun toString(): String = "AppleCredentialReadResult.Ready(redacted)"
    }
    data class Failed(val reason: AppleCredentialFailure) : AppleCredentialReadResult
}

/**
 * Synchronous disk/keystore operations. Callers must move every operation to an IO dispatcher.
 * Construction does not create a file or key. Share one instance within the application process;
 * this contract does not provide cross-process locking or session-generation checks.
 */
interface AppleCredentialStore {
    fun read(): AppleCredentialReadResult
    fun write(credential: StoredAppleCredential): Boolean
    fun clear(): Boolean
}

internal const val APPLE_CREDENTIAL_MAX_FILE_BYTES = 20 * 1024
private const val MAX_TOKEN_BYTES = 16 * 1024
private const val IV_BYTES = 12
private const val TAG_BYTES = 16
private const val HEADER_BYTES = 5
private const val PAYLOAD_FIXED_BYTES = 4 + 2 + 1 + 8 + 1
private val HEADER = byteArrayOf(0x59, 0x4c, 0x41, 0x4d, 1)

internal interface AppleCredentialFile {
    /** Null means absent, other I/O failures throw. Never read more than maxBytes + 1 bytes. */
    fun readBounded(maxBytes: Int): ByteArray?
    /** Commit atomically and verify the committed bytes before returning true. */
    fun writeAtomically(bytes: ByteArray): Boolean
    /** True only when the base file and all its atomic-write companions are absent. */
    fun delete(): Boolean
}

internal class AppleCredentialFileTooLarge : IOException("Apple credential file exceeds limit")

internal interface AppleCredentialKeys {
    /** Look up only; never create a key while reading. */
    fun existing(): SecretKey?
    fun getOrCreate(): SecretKey
    fun delete(): Boolean
}

internal class EncryptedAppleCredentialStore(
    private val file: AppleCredentialFile,
    private val keys: AppleCredentialKeys,
) : AppleCredentialStore {
    private val lock = Any()

    override fun read(): AppleCredentialReadResult = synchronized(lock) {
        val bytes = try {
            file.readBounded(APPLE_CREDENTIAL_MAX_FILE_BYTES)
        } catch (_: AppleCredentialFileTooLarge) {
            return@synchronized failed(AppleCredentialFailure.INVALID)
        } catch (_: Exception) {
            return@synchronized failed(AppleCredentialFailure.STORAGE)
        } ?: return@synchronized AppleCredentialReadResult.Missing

        if (bytes.size !in (HEADER_BYTES + IV_BYTES + TAG_BYTES + PAYLOAD_FIXED_BYTES + 1)..
            APPLE_CREDENTIAL_MAX_FILE_BYTES || HEADER.indices.any { bytes[it] != HEADER[it] }
        ) return@synchronized failed(AppleCredentialFailure.INVALID)

        val key = try {
            keys.existing()
        } catch (_: Exception) {
            return@synchronized failed(AppleCredentialFailure.KEY)
        } ?: return@synchronized failed(AppleCredentialFailure.KEY)

        val plaintext = try {
            if (key.algorithm != "AES") return@synchronized failed(AppleCredentialFailure.CRYPTO)
            val iv = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv))
            if (!parametersMatch(cipher, iv)) return@synchronized failed(AppleCredentialFailure.CRYPTO)
            cipher.updateAAD(HEADER)
            cipher.doFinal(bytes, HEADER_BYTES + IV_BYTES, bytes.size - HEADER_BYTES - IV_BYTES)
        } catch (_: Exception) {
            return@synchronized failed(AppleCredentialFailure.CRYPTO)
        }
        try {
            val credential = decode(plaintext) ?: return@synchronized failed(AppleCredentialFailure.INVALID)
            AppleCredentialReadResult.Ready(credential)
        } catch (_: Exception) {
            failed(AppleCredentialFailure.INVALID)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun write(credential: StoredAppleCredential): Boolean = synchronized(lock) {
        // Validate and serialize before even looking up a key or touching the file.
        val plaintext = try {
            encode(credential)
        } catch (_: Exception) {
            null
        } ?: return@synchronized false
        try {
            val key = keys.getOrCreate()
            if (key.algorithm != "AES") return@synchronized false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // No caller-supplied IV: each encryption asks the provider for a fresh one.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: return@synchronized false
            if (!parametersMatch(cipher, iv)) return@synchronized false
            cipher.updateAAD(HEADER)
            val encrypted = cipher.doFinal(plaintext)
            if (encrypted.size != plaintext.size + TAG_BYTES) return@synchronized false
            file.writeAtomically(HEADER + iv + encrypted)
        } catch (_: Exception) {
            false
        } finally {
            plaintext.fill(0)
        }
    }

    override fun clear(): Boolean = synchronized(lock) {
        val fileDeleted = try { file.delete() } catch (_: Exception) { false }
        // Do not short-circuit: revoking the key is still useful if the file cannot be removed.
        val keyDeleted = try { keys.delete() } catch (_: Exception) { false }
        fileDeleted && keyDeleted
    }

    private fun parametersMatch(cipher: Cipher, iv: ByteArray): Boolean {
        val spec = cipher.parameters?.getParameterSpec(GCMParameterSpec::class.java) ?: return false
        return iv.size == IV_BYTES && spec.tLen == TAG_BYTES * 8 &&
            spec.iv.contentEquals(iv) && cipher.iv.contentEquals(iv)
    }

    private fun valid(credential: StoredAppleCredential): Boolean =
        credential.token.isNotEmpty() && credential.token.length <= MAX_TOKEN_BYTES &&
            credential.token == credential.token.trim() && credential.token.none { it.isISOControl() } &&
            credential.storefront.length == 2 && credential.storefront.all { it in 'a'..'z' } &&
            credential.verifiedAtEpochMillis >= 0 &&
            (credential.cookieExpiresAtEpochMillis == null || credential.cookieExpiresAtEpochMillis >= 0)

    private fun encode(credential: StoredAppleCredential): ByteArray? {
        if (!valid(credential)) return null
        val token = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(credential.token))
        try {
            if (token.remaining() > MAX_TOKEN_BYTES) return null
            val expiry = credential.cookieExpiresAtEpochMillis
            return ByteBuffer.allocate(PAYLOAD_FIXED_BYTES + token.remaining() + if (expiry == null) 0 else 8)
                .order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(token.remaining())
                    put(token)
                    put(credential.storefront[0].code.toByte())
                    put(credential.storefront[1].code.toByte())
                    put(if (credential.source == AppleCredentialSource.MANUAL) 0.toByte() else 1.toByte())
                    putLong(credential.verifiedAtEpochMillis)
                    put(if (expiry == null) 0.toByte() else 1.toByte())
                    if (expiry != null) putLong(expiry)
                }.array()
        } finally {
            if (token.hasArray()) token.array().fill(0)
        }
    }

    private fun decode(bytes: ByteArray): StoredAppleCredential? {
        if (bytes.size < PAYLOAD_FIXED_BYTES + 1) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val length = buffer.int
        if (length !in 1..MAX_TOKEN_BYTES || length > buffer.remaining() - (PAYLOAD_FIXED_BYTES - 4)) return null
        val tokenBytes = buffer.slice().apply { limit(length) }
        val token = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(tokenBytes).toString()
        buffer.position(buffer.position() + length)
        val first = buffer.get().toInt()
        val second = buffer.get().toInt()
        if (first !in 'a'.code..'z'.code || second !in 'a'.code..'z'.code) return null
        val storefront = "${first.toChar()}${second.toChar()}"
        val source = when (buffer.get().toInt()) {
            0 -> AppleCredentialSource.MANUAL
            1 -> AppleCredentialSource.WEB
            else -> return null
        }
        val verified = buffer.long
        val expiry = when (buffer.get().toInt()) {
            0 -> null
            1 -> {
                if (buffer.remaining() < 8) return null
                buffer.long
            }
            else -> return null
        }
        if (buffer.hasRemaining()) return null
        return StoredAppleCredential(token, storefront, source, verified, expiry).takeIf(::valid)
    }

    private fun failed(reason: AppleCredentialFailure) = AppleCredentialReadResult.Failed(reason)
}
