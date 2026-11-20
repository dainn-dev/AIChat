package com.aichat.keyboard.core.network.auth

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the serialized session before it touches the disk. */
interface TokenCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    /** @throws GeneralSecurityException when the data was tampered with, or the key is gone or invalid. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a fresh random 96-bit IV per message, provided by the cipher itself (Android Keystore keys
 * require `randomizedEncryptionRequired`, so the IV can never be chosen by the caller).
 *
 * Blob layout: `[version:1][ivLength:1][iv][ciphertext + 128-bit tag]`. [associatedData] binds a blob to its
 * purpose, so a ciphertext produced for something else cannot be swapped in.
 *
 * [keyProvider] returns the key: an Android Keystore key in the app ([AndroidKeystoreKeys]), or a software key
 * in JVM tests. Plain `javax.crypto` keeps the format testable on the JVM.
 */
class AesGcmTokenCipher(
    private val keyProvider: () -> SecretKey,
    private val associatedData: ByteArray = DEFAULT_AAD,
) : TokenCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(2 + iv.size + sealed.size)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(sealed)
            .array()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < 2) throw GeneralSecurityException("Token blob too short")
        val buffer = ByteBuffer.wrap(ciphertext)
        val version = buffer.get()
        if (version != FORMAT_VERSION) throw GeneralSecurityException("Unsupported token blob version $version")
        val ivLength = buffer.get().toInt() and 0xFF
        if (ivLength != GCM_IV_BYTES || buffer.remaining() <= ivLength) {
            throw GeneralSecurityException("Malformed token blob")
        }
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        val sealed = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        private const val FORMAT_VERSION: Byte = 1
        private val DEFAULT_AAD = "com.aichat.keyboard/session/v1".toByteArray(Charsets.UTF_8)
    }
}
