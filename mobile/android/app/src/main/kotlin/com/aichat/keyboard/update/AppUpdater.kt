/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.update

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the updater is doing, for the "Cập nhật ứng dụng" screen. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: AppRelease) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val reason: Reason) : UpdateState

    enum class Reason { NETWORK, VERIFY, INSTALL }
}

/**
 * In-app updates from the repository's GitHub Releases (the app is sideloaded, not on Play).
 *
 * - **Check:** at most every [CHECK_INTERVAL_MS], when the keyboard is hidden ([onKeyboardHidden]), or on demand
 *   from the update screen. Only a newer `android-v…-build…` release counts ([ReleaseFeed]).
 * - **Download:** automatically on an unmetered network only; on mobile data a notification offers the update.
 * - **Verify:** the APK must have this app's package name, the release's version code and **the same signing
 *   certificate** as the installed app, so a tampered release is never installed (Android would refuse a different
 *   key anyway; this fails earlier and keeps the file off the installer).
 * - **Install:** a [PackageInstaller] session. Installing replaces the running process, so the automatic path
 *   only installs while the keyboard is hidden and no settings screen is open. Android 12+ installs without asking
 *   once this app is the installer of record, i.e. from the second self-update on; otherwise the system asks the
 *   user to confirm ([UpdateInstallReceiver]).
 *
 * Release (non-suffixed) builds only: debug and beta have other package names and are never offered an update.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_FEED_BYTES = 2 * 1024 * 1024
    private const val USER_AGENT = "AIChatKeyboard-Updater"
    private const val UPDATE_DIR = "updates"

    private const val PREFS = "aichat_updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_INSTALLING_CODE = "installing_code"

    internal const val EXTRA_INTERACTIVE = "interactive"
    internal const val EXTRA_VERSION_NAME = "version_name"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var keyboardVisible = false

    @Volatile
    private var resumedActivities = 0

    val isSupported: Boolean
        get() = !BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == BuildConfig.BASE_APPLICATION_ID

    val currentVersionCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    /** Call once from `Application.onCreate`: tracks open screens and clears APKs that are already installed. */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(ResumedActivityCounter)
        if (!isSupported) return
        scope.launch { deleteStaleApks(application) }
    }

    fun onKeyboardShown() {
        keyboardVisible = true
    }

    /** The automatic path: check (throttled), download on Wi-Fi, install while nothing of the app is on screen. */
    fun onKeyboardHidden(context: Context) {
        keyboardVisible = false
        if (!isSupported || !autoUpdateEnabled()) return
        val appContext = context.applicationContext
        scope.launch {
            if (mutex.isLocked) return@launch
            mutex.withLock { autoUpdate(appContext) }
        }
    }

    /** "Kiểm tra cập nhật" on the update screen. */
    fun checkNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                val release = check(appContext, force = true) ?: return@withLock
                if (readyApk(appContext, release) != null) _state.value = UpdateState.ReadyToInstall(release)
            }
        }
    }

    /** "Cập nhật" on the update screen: download if needed, then install with the system confirmation if asked. */
    fun updateNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                val release = when (val current = _state.value) {
                    is UpdateState.Available -> current.release
                    is UpdateState.ReadyToInstall -> current.release
                    else -> check(appContext, force = true)
                } ?: return@withLock
                val apk = readyApk(appContext, release) ?: download(appContext, release) ?: return@withLock
                startInstall(appContext, release, apk, interactive = true)
            }
        }
    }

    private suspend fun autoUpdate(context: Context) {
        val release = check(context, force = false) ?: return
        val apk = readyApk(context, release) ?: run {
            if (isMetered(context)) {
                UpdateNotifications.showAvailable(context, release)
                return
            }
            download(context, release)
        } ?: return
        if (keyboardVisible || resumedActivities > 0) {
            // Installing would close what the user is looking at; the next time the keyboard hides, the verified
            // file is already there.
            _state.value = UpdateState.ReadyToInstall(release)
            return
        }
        startInstall(context, release, apk, interactive = false)
    }

    /** The newer release, or null if there is none (or it is not time to check yet). */
    private suspend fun check(context: Context, force: Boolean): AppRelease? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0)
            if (now - last in 0 until CHECK_INTERVAL_MS) {
                return (_state.value as? UpdateState.Available)?.release
                    ?: (_state.value as? UpdateState.ReadyToInstall)?.release
            }
        }
        _state.value = UpdateState.Checking
        val body = try {
            withContext(Dispatchers.IO) { fetchFeed() }
        } catch (e: IOException) {
            Log.w(TAG, "Update check failed: ${e.javaClass.simpleName}")
            _state.value = UpdateState.Failed(UpdateState.Reason.NETWORK)
            return null
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        val release = ReleaseFeed.updateFrom(body, currentVersionCode)
        _state.value = if (release != null) UpdateState.Available(release) else UpdateState.UpToDate
        return release
    }

    private fun fetchFeed(): String {
        val connection = open(ReleaseFeed.RELEASES_URL)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { it.readNBytesCompat(MAX_FEED_BYTES + 1) }
            if (bytes.size > MAX_FEED_BYTES) throw IOException("Feed too large")
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(context: Context, release: AppRelease): File? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, UPDATE_DIR).apply { mkdirs() }
        val target = apkFile(context, release)
        val partial = File(dir, "${target.name}.part")
        _state.value = UpdateState.Downloading(release, 0f)
        try {
            val connection = open(release.apkUrl)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
                var copied = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > release.apkSize) throw IOException("Larger than announced")
                            output.write(buffer, 0, read)
                            _state.value = UpdateState.Downloading(release, copied.toFloat() / release.apkSize)
                        }
                    }
                }
                if (copied != release.apkSize) throw IOException("Size mismatch")
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Update download failed: ${e.javaClass.simpleName}")
            partial.delete()
            _state.value = UpdateState.Failed(UpdateState.Reason.NETWORK)
            return@withContext null
        }
        if (!isTrustedApk(context, partial, release) || !partial.renameTo(target)) {
            Log.w(TAG, "Downloaded update failed verification")
            partial.delete()
            _state.value = UpdateState.Failed(UpdateState.Reason.VERIFY)
            return@withContext null
        }
        _state.value = UpdateState.ReadyToInstall(release)
        target
    }

    /** The already downloaded and still valid APK for [release], if any. */
    private fun readyApk(context: Context, release: AppRelease): File? {
        val file = apkFile(context, release)
        if (!file.isFile) return null
        if (isTrustedApk(context, file, release)) return file
        file.delete()
        return null
    }

    private fun apkFile(context: Context, release: AppRelease) =
        File(File(context.filesDir, UPDATE_DIR), "aichat-${release.versionCode}.apk")

    private fun isTrustedApk(context: Context, file: File, release: AppRelease): Boolean {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: return false
        if (archive.packageName != context.packageName) return false
        if (PackageInfoCompat.getLongVersionCode(archive) != release.versionCode) return false
        if (release.versionCode <= currentVersionCode) return false
        val installed = installedInfo(pm, context.packageName) ?: return false
        val expected = signerDigests(installed)
        return expected.isNotEmpty() && signerDigests(archive) == expected
    }

    private fun startInstall(context: Context, release: AppRelease, apk: File, interactive: Boolean) {
        _state.value = UpdateState.Installing(release)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        try {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_INSTALLING_CODE, release.versionCode).apply()
                val callback = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_STATUS)
                    .putExtra(EXTRA_INTERACTIVE, interactive)
                    .putExtra(EXTRA_VERSION_NAME, release.versionName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    // Mutable: the installer adds the status extras.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: IOException) {
            installError(e)
        } catch (e: SecurityException) {
            installError(e)
        }
    }

    private fun installError(e: Exception) {
        Log.w(TAG, "Update install failed: ${e.javaClass.simpleName}")
        _state.value = UpdateState.Failed(UpdateState.Reason.INSTALL)
    }

    /**
     * The session ended without installing. Cancelled by the user ([aborted]): back to "ready", so the update
     * screen offers it again. (On success the process is replaced, so there is no success callback to handle.)
     */
    internal fun onInstallFailed(aborted: Boolean) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = if (aborted && release != null) {
            UpdateState.ReadyToInstall(release)
        } else {
            UpdateState.Failed(UpdateState.Reason.INSTALL)
        }
    }

    /** After an update: true (once) if this app installed it, so "Đã cập nhật" is only shown for our own updates. */
    internal fun consumeInstalledByUs(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getLong(KEY_INSTALLING_CODE, -1)
        prefs.edit().remove(KEY_INSTALLING_CODE).apply()
        return code == currentVersionCode
    }

    private fun deleteStaleApks(context: Context) {
        File(context.filesDir, UPDATE_DIR).listFiles()?.forEach { file ->
            val code = file.name.removePrefix("aichat-").substringBefore('.').toLongOrNull()
            if (code == null || code <= currentVersionCode || file.name.endsWith(".part")) file.delete()
        }
    }

    private fun autoUpdateEnabled(): Boolean = try {
        val prefs by FlorisPreferenceStore
        prefs.aichatUpdates.autoUpdate.get()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Preferences not loaded yet: try again next time.
        false
    }

    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.isActiveNetworkMetered
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        // A generic client name only: no user or device details go to GitHub.
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** SHA-256 of each current signing certificate. */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return signatures.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private object ResumedActivityCounter : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivities++
        }

        override fun onActivityPaused(activity: Activity) {
            resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
