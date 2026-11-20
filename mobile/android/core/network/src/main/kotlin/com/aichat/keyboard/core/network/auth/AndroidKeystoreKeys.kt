package com.aichat.keyboard.core.network.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Non-exportable AES-256 key in the Android Keystore (hardware-backed where the device supports it).
 *
 * Deliberately NOT set:
 * - `setUserAuthenticationRequired`: the IME must read the session in the background without a biometric prompt.
 * - `setUnlockedDeviceRequired`: the IME is direct-boot aware and may run on the lock screen; decryption there
 *   would fail. The token file itself lives in credential-encrypted storage, so it is unreadable before the first
 *   unlock anyway.
 */
internal object AndroidKeystoreKeys {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_SIZE_BITS = 256

    @Synchronized
    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
