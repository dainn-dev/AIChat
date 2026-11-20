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
 * - A file that cannot be decrypted (key invalidated, tampered, corrupt) reads as "signed out". It is left in
 *   place and overwritten by the next login.
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
            val blob = try {
                cipher.encrypt(plaintext)
            } catch (e: GeneralSecurityException) {
                throw IOException("Could not encrypt the session", e)
            } catch (e: RuntimeException) {
                // Keystore failures surface as ProviderException and friends. Callers (including OkHttp's
                // Authenticator thread) only expect IOException, so never let these escape.
                throw IOException("Could not encrypt the session", e)
            }
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

    private fun readFromDisk(): AuthTokens? {
        if (!file.exists()) return null
        return try {
            val plaintext = cipher.decrypt(file.readBytes())
            json.decodeFromString(AuthTokens.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            null // tampered, or the Keystore key was invalidated
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null // Keystore ProviderException, or a SerializationException for a corrupt payload
        }
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
