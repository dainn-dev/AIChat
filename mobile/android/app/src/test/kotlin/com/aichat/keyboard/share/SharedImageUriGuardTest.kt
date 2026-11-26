package com.aichat.keyboard.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedImageUriGuardTest {
    private val pkg = "com.aichat.keyboard"
    private val ownProvider = "$pkg.provider.file"
    private val mediaDocs = "com.android.providers.media.documents"

    /** Resolves like PackageManager would: exact authority match only, null for unknown providers. */
    private val providers = mapOf(
        ownProvider to pkg,
        mediaDocs to "com.android.providers.media.module",
    )
    private val resolveOwner: (String) -> String? = { providers[it] }

    private fun accepts(scheme: String?, authority: String?, resolve: (String) -> String? = resolveOwner) =
        SharedImageUriGuard.accepts(scheme, authority, ownPackage = pkg, resolveOwner = resolve)

    @Test
    fun `a third-party media document is accepted`() {
        assertTrue(accepts("content", mediaDocs))
    }

    @Test
    fun `an unresolvable third-party provider without user info is accepted`() {
        assertTrue(accepts("content", "com.example.gallery.fileprovider", resolve = { null }))
    }

    @Test
    fun `our own provider is refused`() {
        assertFalse(accepts("content", ownProvider))
    }

    @Test
    fun `our own provider behind a user id prefix is refused`() {
        assertFalse(accepts("content", "0@$ownProvider"))
    }

    @Test
    fun `our own provider with a mixed-case authority is refused`() {
        assertFalse(accepts("content", "Com.AIChat.Keyboard.Provider.File", resolve = { null }))
    }

    @Test
    fun `a provider resolved to our package under another name is refused`() {
        assertFalse(accepts("content", "innocent.looking.authority", resolve = { pkg }))
    }

    @Test
    fun `user info on an unresolvable provider is refused`() {
        assertFalse(accepts("content", "10@com.example.unknown", resolve = { null }))
    }

    @Test
    fun `a third-party provider with a user id that resolves elsewhere is accepted`() {
        assertTrue(accepts("content", "0@$mediaDocs"))
    }

    @Test
    fun `non-content schemes and missing parts are refused`() {
        assertFalse(accepts("file", ""))
        assertFalse(accepts("file", null))
        assertFalse(accepts("https", mediaDocs))
        assertFalse(accepts(null, null))
        assertFalse(accepts("content", null))
        assertFalse(accepts("content", ""))
        assertFalse(accepts("content", "0@"))
    }

    @Test
    fun `the resolver is asked with the user id stripped`() {
        val asked = mutableListOf<String>()
        accepts("content", "0@$mediaDocs", resolve = { asked += it; resolveOwner(it) })
        assertEquals(listOf(mediaDocs), asked)
    }

    @Test
    fun `stripUserInfo keeps the host part`() {
        assertEquals(ownProvider, SharedImageUriGuard.stripUserInfo("0@$ownProvider"))
        assertEquals(mediaDocs, SharedImageUriGuard.stripUserInfo(mediaDocs))
        assertNull(SharedImageUriGuard.stripUserInfo("0@"))
        assertNull(SharedImageUriGuard.stripUserInfo(null))
    }
}
