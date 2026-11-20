package com.aichat.keyboard.core.network.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * [TokenStore] that keeps the session as one [TokenCipher]-encrypted file, with an in-memory copy.
 *
 * One instance per process (see `AiChat.client(context)`), so the settings activity and the IME service see the
 * same session and the same [tokens] flow.
 *
 * - Writes go to a temp file that is then renamed over the target, so a crash mid-write never leaves a half
 *   file behind.
 * - A file that cannot be decrypted (key invalidated or corrupted, tampered, garbage) reads as "signed out":
 *   the key is reset ([TokenCipher.reset]) and the file deleted, so the next login starts from a fresh key
 *   instead of failing forever on a broken one.
 * - If encryption fails (broken key), the key is reset and encryption retried once with a fresh key.
 * - [isStorageAvailable] covers direct boot: before the first unlock the credential-encrypted directory is not
 *   readable. The store then reports "signed out" without caching that answer, and loads for real once the
 *   user has unlocked.
 */
class EncryptedFileTokenStore(
    private val file: File,
    private val cipher: TokenCipher,
    private val isStorageAvailable: () -> Boolean = { true },
) : TokenStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow<AuthTokens?>(null)
    private var loaded = false

    override val tokens: StateFlow<AuthTokens?> = state.asStateFlow()

    override fun current(): AuthTokens? = synchronized(lock) {
        ensureLoaded()
        state.value
    }

    override fun save(tokens: AuthTokens): Unit = synchronized(lock) {
        write(tokens)
    }

    override fun clear(): Unit = synchronized(lock) {
        write(null)
    }

    override fun compareAndSet(expected: AuthTokens?, new: AuthTokens?): Boolean = synchronized(lock) {
        ensureLoaded()
        if (state.value != expected) return false
        write(new)
        true
    }

    /** Must hold [lock]. Persists first, then publishes, so memory never runs ahead of the disk. */
    private fun write(tokens: AuthTokens?) {
        if (tokens == null) {
            if (file.exists() && !file.delete()) throw IOException("Could not delete ${file.name}")
        } else {
            val plaintext = json.encodeToString(AuthTokens.serializer(), tokens).toByteArray(Charsets.UTF_8)
            val blob = encryptOrNull(plaintext)
                ?: run { cipher.resetQuietly(); encryptOrNull(plaintext) } // broken key: retry once with a new one
                ?: throw IOException("Could not encrypt the session")
            writeAtomically(blob)
        }
        state.value = tokens
        loaded = true
    }

    /** Must hold [lock]. */
    private fun ensureLoaded() {
        if (loaded || !isStorageAvailable()) return
        state.value = readFromDisk()
        loaded = true
    }

    /**
     * Encrypts, mapping every crypto failure to null. Keystore failures surface as GeneralSecurityException
     * (e.g. KeyPermanentlyInvalidatedException, UnrecoverableKeyException) or as RuntimeException
     * (ProviderException). Callers, including OkHttp's Authenticator thread, only expect IOException, so none of
     * them may escape.
     */
    private fun encryptOrNull(plaintext: ByteArray): ByteArray? = try {
        cipher.encrypt(plaintext)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun TokenCipher.resetQuietly() {
        try {
            reset()
        } catch (_: RuntimeException) {
            // Nothing more we can do; the next write tries again.
        }
    }

    /** Must hold [lock]. */
    private fun readFromDisk(): AuthTokens? {
        if (!file.exists()) return null
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            return null // transient I/O problem: keep the file, try again on the next start
        }
        return try {
            val plaintext = cipher.decrypt(bytes)
            json.decodeFromString(AuthTokens.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            discardUnreadableSession() // tampered, or the Keystore key is invalidated or corrupted
        } catch (_: RuntimeException) {
            // Trade-off: every RuntimeException here (Keystore ProviderException, SerializationException for garbage,
            // or an unexpected bug) is treated as an unreadable session. The cost is a forced sign-in, which is
            // annoying but safe; keeping the file instead could block sign-in forever behind a key that will never
            // decrypt it again (security review M1).
            discardUnreadableSession()
        }
    }

    /** Must hold [lock]. The session is lost either way; start over with a fresh key and no file. */
    private fun discardUnreadableSession(): AuthTokens? {
        cipher.resetQuietly()
        file.delete()
        return null
    }

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.let { dir -> if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create $dir") }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            // File.renameTo does not replace an existing target on every platform (e.g. Windows JVMs in tests).
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Could not move ${tmp.name} to ${file.name}")
            }
        }
    }
}
