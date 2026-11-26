package com.aichat.keyboard.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseFeedTest {
    private fun release(
        tag: String,
        apk: String? = "AIChatKeyboard.apk",
        url: String = ReleaseFeed.DOWNLOAD_PREFIX + "$tag/AIChatKeyboard.apk",
        size: Long = 35_000_000,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = GitHubRelease(
        tagName = tag,
        draft = draft,
        prerelease = prerelease,
        assets = listOfNotNull(apk?.let { GitHubAsset(it, url, size) }),
    )

    @Test
    fun `the highest build wins, whatever the list order`() {
        val newest = ReleaseFeed.newest(
            listOf(
                release("android-v0.5.2-build117"),
                release("android-v0.6.1-build120"),
                release("android-v0.6.0-build118"),
            ),
        )

        assertEquals(AppRelease("0.6.1", 120, ReleaseFeed.DOWNLOAD_PREFIX + "android-v0.6.1-build120/AIChatKeyboard.apk", 35_000_000), newest)
    }

    @Test
    fun `drafts, pre-releases, other tags and releases without one apk are ignored`() {
        val newest = ReleaseFeed.newest(
            listOf(
                release("android-v0.6.0-build118"),
                release("android-v0.9.0-build200", draft = true),
                release("android-v0.9.0-build201", prerelease = true),
                release("backend-v2.0.0"),
                release("android-v0.9.0-build202", apk = null),
                release("android-v0.9.0-build203", apk = "notes.txt"),
            ),
        )

        assertEquals(118, newest?.versionCode)
    }

    @Test
    fun `an apk hosted anywhere else is refused`() {
        assertNull(ReleaseFeed.newest(listOf(release("android-v0.6.0-build118", url = "https://evil.example/x.apk"))))
        assertNull(
            ReleaseFeed.newest(
                listOf(release("android-v0.6.0-build118", url = "http://github.com/dainn-dev/AIChat/releases/download/x.apk")),
            ),
        )
        assertNull(ReleaseFeed.newest(listOf(release("android-v0.6.0-build118", size = 0))))
    }

    @Test
    fun `parses the GitHub API body and only offers a newer build`() {
        val body = """
            [
              {"tag_name": "android-v0.6.0-build118", "draft": false, "prerelease": false, "name": "x",
               "assets": [{"name": "AIChatKeyboard-0.6.0-118.apk", "size": 35237027, "state": "uploaded",
                 "browser_download_url": "https://github.com/dainn-dev/AIChat/releases/download/android-v0.6.0-build118/AIChatKeyboard-0.6.0-118.apk"}]},
              {"tag_name": "android-v0.5.2-build117", "assets": []}
            ]
        """.trimIndent()

        assertEquals("0.6.0", ReleaseFeed.updateFrom(body, currentVersionCode = 117)?.versionName)
        assertNull(ReleaseFeed.updateFrom(body, currentVersionCode = 118))
        assertNull(ReleaseFeed.updateFrom(body, currentVersionCode = 119))
    }

    @Test
    fun `a body that is not a release list gives nothing`() {
        assertNull(ReleaseFeed.newest("""{"message": "API rate limit exceeded"}"""))
        assertNull(ReleaseFeed.newest("<html>"))
        assertNull(ReleaseFeed.newest("[]"))
    }
}
