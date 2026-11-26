/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One entry of GitHub's `GET /repos/{owner}/{repo}/releases`, only the fields the updater reads. */
@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

/** A published Android build the app can update to. */
data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSize: Long,
)

/**
 * Picks the newest Android build out of the repository's GitHub releases. Pure Kotlin so it is unit-tested.
 *
 * Only published releases (no drafts, no pre-releases) tagged `android-v<versionName>-build<versionCode>` with one
 * `.apk` asset under [DOWNLOAD_PREFIX] count; the backend or anything else released in the same repository is
 * ignored. The version code comes from the tag; the downloaded APK is checked against it before installing.
 */
object ReleaseFeed {
    const val RELEASES_URL = "https://api.github.com/repos/dainn-dev/AIChat/releases?per_page=20"
    const val DOWNLOAD_PREFIX = "https://github.com/dainn-dev/AIChat/releases/download/"

    private val tagPattern = Regex("""^android-v(\d+(?:\.\d+){0,3})-build(\d{1,9})$""")
    private val json = Json { ignoreUnknownKeys = true }

    /** The newest release in [body] (the API's JSON), or null if there is none or the body is not valid. */
    fun newest(body: String): AppRelease? {
        val releases = try {
            json.decodeFromString<List<GitHubRelease>>(body)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return newest(releases)
    }

    internal fun newest(releases: List<GitHubRelease>): AppRelease? =
        releases.mapNotNull(::toAppRelease).maxByOrNull { it.versionCode }

    /** The newest release if it is newer than [currentVersionCode]. */
    fun updateFrom(body: String, currentVersionCode: Long): AppRelease? =
        newest(body)?.takeIf { it.versionCode > currentVersionCode }

    private fun toAppRelease(release: GitHubRelease): AppRelease? {
        if (release.draft || release.prerelease) return null
        val match = tagPattern.matchEntire(release.tagName) ?: return null
        val apk = release.assets.singleOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        if (!apk.downloadUrl.startsWith(DOWNLOAD_PREFIX) || apk.size <= 0) return null
        return AppRelease(
            versionName = match.groupValues[1],
            versionCode = match.groupValues[2].toLong(),
            apkUrl = apk.downloadUrl,
            apkSize = apk.size,
        )
    }
}
