package com.aichat.keyboard.core.network

import android.content.Context
import android.os.UserManager
import com.aichat.keyboard.core.network.auth.AesGcmTokenCipher
import com.aichat.keyboard.core.network.auth.AndroidKeystoreKeys
import com.aichat.keyboard.core.network.auth.EncryptedFileTokenStore
import java.io.File

/**
 * Per-process entry point on Android.
 *
 * The settings activity (`FlorisAppActivity`) and the IME service (`FlorisImeService`) run in the same app
 * process, so both get this one [AiChatClient] and one token store: a login in settings is immediately visible
 * to the keyboard, and a forced sign-out in the keyboard is immediately visible in settings.
 *
 * The session file lives in `noBackupFilesDir` (never part of Android backup, on top of the app-wide
 * `allowBackup="false"` and `data_extraction_rules.xml`) and is AES-GCM encrypted with a non-exportable
 * Android Keystore key.
 */
object AiChat {
    private const val KEY_ALIAS = "aichat_session_key_v1"
    private const val SESSION_DIR = "aichat"
    private const val SESSION_FILE = "session.bin"

    @Volatile
    private var client: AiChatClient? = null

    /** The API base URL for this build type (see `core/network/build.gradle.kts`). */
    val baseUrl: String get() = BuildConfig.AICHAT_API_BASE_URL

    fun client(context: Context): AiChatClient =
        client ?: synchronized(this) {
            client ?: create(context.applicationContext).also { client = it }
        }

    private fun create(appContext: Context): AiChatClient {
        val userManager = appContext.getSystemService(UserManager::class.java)
        val tokenStore = EncryptedFileTokenStore(
            file = File(File(appContext.noBackupFilesDir, SESSION_DIR), SESSION_FILE),
            cipher = AesGcmTokenCipher(keyProvider = { AndroidKeystoreKeys.getOrCreate(KEY_ALIAS) }),
            isStorageAvailable = { userManager?.isUserUnlocked ?: true },
        )
        return AiChatClientFactory.create(baseUrl = baseUrl, tokenStore = tokenStore)
    }
}
