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

/** Loads [ViGlideLexicon] once from assets, off the main thread. A failed load is retried by the next [get]. */
object ViGlideLexiconLoader {
    private const val VI_ASSET = "ime/dict/vi_glide.tsv"
    private const val ENGLISH_ASSET = "ime/dict/data.json"

    private val mutex = Mutex()

    @Volatile
    private var instance: ViGlideLexicon? = null

    fun peek(): ViGlideLexicon? = instance

    suspend fun get(context: Context): ViGlideLexicon {
        instance?.let { return it }
        return mutex.withLock {
            instance ?: withContext(Dispatchers.IO) { load(context.applicationContext) }.also { instance = it }
        }
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
