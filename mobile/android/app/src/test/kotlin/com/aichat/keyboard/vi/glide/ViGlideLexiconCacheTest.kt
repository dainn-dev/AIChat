/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ViGlideLexiconCacheTest {
    private fun tiny() = ViGlideLexicon.parse(sequenceOf("U\tvà\t10"), emptyMap())

    @Test
    fun `peek is null before load and set after`() = runBlocking {
        val cache = ViGlideLexiconCache { tiny() }
        assertNull(cache.peek())
        val lex = cache.get()
        assertSame(lex, cache.peek())
    }

    @Test
    fun `get memoizes`() = runBlocking {
        val calls = AtomicInteger()
        val cache = ViGlideLexiconCache { calls.incrementAndGet(); tiny() }
        assertSame(cache.get(), cache.get())
        assertEquals(1, calls.get())
    }

    @Test
    fun `failed load leaves peek null and next get retries`() = runBlocking {
        val calls = AtomicInteger()
        val cache = ViGlideLexiconCache {
            if (calls.incrementAndGet() == 1) error("boom")
            tiny()
        }
        assertFailsWith<IllegalStateException> { cache.get() }
        assertNull(cache.peek())
        assertSame(cache.get(), cache.peek())
        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent gets load once`() = runBlocking {
        val calls = AtomicInteger()
        val cache = ViGlideLexiconCache { calls.incrementAndGet(); delay(50); tiny() }
        val results = (1..8).map { async(Dispatchers.Default) { cache.get() } }.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(1, results.toSet().size)
    }
}
