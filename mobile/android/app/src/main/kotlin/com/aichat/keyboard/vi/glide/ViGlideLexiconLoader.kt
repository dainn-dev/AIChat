/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import android.content.Context
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Memoizes one [ViGlideLexicon] built by [load]. A failed load is not cached, so the next [get]
 * retries. Pure JVM so the contract can be unit-tested; [ViGlideLexiconLoader] supplies the assets.
 */
internal class ViGlideLexiconCache(private val load: suspend () -> ViGlideLexicon) {
    private val mutex = Mutex()

    @Volatile
    private var instance: ViGlideLexicon? = null

    fun peek(): ViGlideLexicon? = instance

    suspend fun get(): ViGlideLexicon {
        instance?.let { return it }
        return mutex.withLock {
            instance ?: load().also { instance = it }
        }
    }
}

/** Loads [ViGlideLexicon] once from assets, off the main thread. */
object ViGlideLexiconLoader {
    private const val VI_ASSET = "ime/dict/vi_glide.tsv"
    private const val ENGLISH_ASSET = "ime/dict/data.json"

    @Volatile
    private var appContext: Context? = null

    private val cache = ViGlideLexiconCache {
        val context = checkNotNull(appContext) { "ViGlideLexiconLoader.get was not called" }
        withContext(Dispatchers.IO) { load(context) }
    }

    fun peek(): ViGlideLexicon? = cache.peek()

    suspend fun get(context: Context): ViGlideLexicon {
        appContext = context.applicationContext
        return cache.get()
    }

    private fun load(context: Context): ViGlideLexicon {
        try {
            val assets = context.assets
            val english = assets.open(ENGLISH_ASSET).bufferedReader(Charsets.UTF_8).use {
                Json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), it.readText())
            }
            return assets.open(VI_ASSET).bufferedReader(Charsets.UTF_8).useLines {
                ViGlideLexicon.parse(it, english)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flogWarning { "Glide lexicon load failed: ${e.javaClass.simpleName}" }
            throw e
        }
    }
}
