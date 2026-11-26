/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.share

import java.util.Locale

/**
 * Decides whether a `content:` URI handed to the exported share activity may be read. Pure, no Android types.
 *
 * URIs served by this app's own providers (clipboard media, stickers, files) are refused: another app must not be
 * able to make the share activity read our private files. `ContentResolver` ignores a `userId@` prefix in the
 * authority (`content://0@<pkg>.provider.file/...`), so it is stripped before any check, the comparison ignores
 * case, and the provider's real owner is asked from the package manager.
 */
internal object SharedImageUriGuard {
    private const val SCHEME_CONTENT = "content"

    /**
     * @param scheme the URI scheme.
     * @param authority the raw (encoded) authority, possibly with a `userinfo@` prefix.
     * @param ownPackage this app's package name.
     * @param resolveOwner the package that serves an authority (`PackageManager.resolveContentProvider`), or null.
     */
    fun accepts(
        scheme: String?,
        authority: String?,
        ownPackage: String,
        resolveOwner: (String) -> String?,
    ): Boolean {
        if (!SCHEME_CONTENT.equals(scheme, ignoreCase = true)) return false
        val host = stripUserInfo(authority) ?: return false
        val hasUserInfo = authority.orEmpty().contains('@')

        val lowerHost = host.lowercase(Locale.ROOT)
        val lowerPackage = ownPackage.lowercase(Locale.ROOT)
        if (lowerHost == lowerPackage || lowerHost.startsWith("$lowerPackage.")) return false

        val owner = resolveOwner(host)
        if (owner == null) return !hasUserInfo
        return !owner.equals(ownPackage, ignoreCase = true)
    }

    /** The authority without any `userinfo@` prefix, or null when nothing is left. */
    fun stripUserInfo(authority: String?): String? =
        authority?.substringAfterLast('@')?.trim()?.ifEmpty { null }
}
