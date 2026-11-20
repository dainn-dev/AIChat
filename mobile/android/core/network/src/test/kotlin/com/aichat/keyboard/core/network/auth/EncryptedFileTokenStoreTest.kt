package com.aichat.keyboard.core.network.auth

import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM tests for the token store and the AES-GCM blob format. The Android Keystore is swapped for a software
 * AES key; [AesGcmTokenCipher] uses the same javax.crypto calls on both.
 */
class EncryptedFileTokenStoreTest {
    private val dir: File = Files.createTempDirectory("aichat-tokens").toFile()
    private val file = File(dir, "aichat/session.bin")
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmTokenCipher(keyProvider = { key })
    private val tokens = AuthTokens("access-token-value", "refresh-token-value")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `starts signed out when there is no file`() {
        assertNull(EncryptedFileTokenStore(file, cipher).current())
    }

    @Test
    fun `a saved session survives a new store instance (process restart)`() {
        EncryptedFileTokenStore(file, cipher).save(tokens)

        assertEquals(tokens, EncryptedFileTokenStore(file, cipher).current())
    }

    @Test
    fun `tokens are never written in plain text`() {
        EncryptedFileTokenStore(file, cipher).save(tokens)

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("access-token-value"))
        assertFalse(raw.contains("refresh-token-value"))
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun `rotation overwrites the stored refresh token`() {
        val store = EncryptedFileTokenStore(file, cipher)
        store.save(tokens)
        val rotated = AuthTokens("access-2", "refresh-2")

        store.save(rotated)

        assertEquals(rotated, EncryptedFileTokenStore(file, cipher).current())
    }

    @Test
    fun `clear deletes the file and publishes null`() {
        val store = EncryptedFileTokenStore(file, cipher)
        store.save(tokens)
        assertEquals(tokens, store.tokens.value)

        store.clear()

        assertFalse(file.exists())
        assertNull(store.tokens.value)
        assertNull(EncryptedFileTokenStore(file, cipher).current())
    }

    @Test
    fun `a tampered file reads as signed out`() {
        EncryptedFileTokenStore(file, cipher).save(tokens)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(EncryptedFileTokenStore(file, cipher).current())
    }

    @Test
    fun `a file from another key (reinstall, invalidated key) reads as signed out`() {
        EncryptedFileTokenStore(file, cipher).save(tokens)
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertNull(EncryptedFileTokenStore(file, AesGcmTokenCipher(keyProvider = { otherKey })).current())
    }

    @Test
    fun `garbage in the file reads as signed out and the next login overwrites it`() {
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        val store = EncryptedFileTokenStore(file, cipher)
        assertNull(store.current())

        store.save(tokens)

        assertEquals(tokens, EncryptedFileTokenStore(file, cipher).current())
    }

    @Test
    fun `locked storage (direct boot) reads as signed out without caching it`() {
        EncryptedFileTokenStore(file, cipher).save(tokens)
        var unlocked = false
        val store = EncryptedFileTokenStore(file, cipher, isStorageAvailable = { unlocked })

        assertNull(store.current())
        unlocked = true
        assertEquals(tokens, store.current())
    }

    @Test
    fun `compareAndSet only applies when the expected session is current`() {
        val store = EncryptedFileTokenStore(file, cipher)
        store.save(tokens)
        val rotated = AuthTokens("access-2", "refresh-2")

        assertFalse(store.compareAndSet(AuthTokens("stale", "stale"), null))
        assertEquals(tokens, store.current())

        assertTrue(store.compareAndSet(tokens, rotated))
        assertEquals(rotated, EncryptedFileTokenStore(file, cipher).current())

        assertTrue(store.compareAndSet(rotated, null))
        assertNull(store.current())
        assertFalse(file.exists())
    }

    @Test
    fun `cipher round-trips and uses a fresh IV per message`() {
        val plaintext = "hello".toByteArray()
        val a = cipher.encrypt(plaintext)
        val b = cipher.encrypt(plaintext)

        assertFalse(a.contentEquals(b))
        assertContentEquals(plaintext, cipher.decrypt(a))
        assertContentEquals(plaintext, cipher.decrypt(b))
    }

    @Test
    fun `cipher rejects a blob bound to different associated data`() {
        val other = AesGcmTokenCipher(keyProvider = { key }, associatedData = "something-else".toByteArray())

        assertFailsWith<GeneralSecurityException> { other.decrypt(cipher.encrypt("hello".toByteArray())) }
    }

    @Test
    fun `cipher rejects truncated and unknown-version blobs`() {
        val blob = cipher.encrypt("hello".toByteArray())

        assertFailsWith<GeneralSecurityException> { cipher.decrypt(blob.copyOf(5)) }
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(byteArrayOf()) }
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(blob.copyOf().also { it[0] = 9 }) }
    }

    @Test
    fun `toString never leaks tokens`() {
        assertFalse(tokens.toString().contains("token-value"))
    }
}
