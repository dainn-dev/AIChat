/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import com.aichat.vi.ToneStyle
import com.aichat.vi.ViSyllable
import com.aichat.vi.ViText
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.roundToLong

/** A word the glide ranker may output. Vietnamese words are stored canonical MODERN. */
data class ViGlideEntry(val word: String, val count: Long, val english: Boolean)

/**
 * Immutable glide vocabulary: Vietnamese syllable unigrams and bigrams plus the top English words,
 * grouped by their diacritic-free folded key sequence. Pure JVM; see [ViGlideLexiconLoader] for I/O.
 */
class ViGlideLexicon private constructor(
    private val byFolded: Map<String, List<ViGlideEntry>>,
    private val viIds: Map<String, Int>,
    private val viCounts: LongArray,
    private val englishCounts: Map<String, Long>,
    private val bigramKeys: LongArray,
    private val bigramValues: LongArray,
    private val bigramTotals: LongArray,
    private val totalCount: Long,
) {
    /** Distinct folded forms, Vietnamese plus English. */
    val foldedForms: List<String> = byFolded.keys.toList()

    private val foldedWeights: Map<String, Double>

    init {
        val sums = byFolded.mapValues { (_, list) -> list.sumOf { it.count } }
        val denominator = ln(1.0 + (sums.values.maxOrNull() ?: 0L))
        foldedWeights = sums.mapValues { (_, sum) ->
            if (denominator <= 0.0) 0.0 else (ln(1.0 + sum) / denominator).coerceIn(0.0, 1.0)
        }
    }

    /** 0..1: log-scaled weighted count of [folded]'s entries, normalised to the most frequent form. */
    fun foldedFrequency(folded: String): Double = foldedWeights[folded] ?: 0.0

    /** Entries whose folded form is [folded], most frequent first. */
    fun expansions(folded: String): List<ViGlideEntry> = byFolded[folded].orEmpty()

    /** True for a Vietnamese syllable (either tone style) or an English entry, case-insensitively. */
    fun isKnown(word: String): Boolean {
        val s = normalize(word)
        return viId(s) >= 0 || s in englishCounts
    }

    fun unigramProbability(word: String): Double {
        if (totalCount <= 0L) return 0.0
        val s = normalize(word)
        val id = viId(s)
        val count = (if (id >= 0) viCounts[id] else 0L) + (englishCounts[s] ?: 0L)
        return count.toDouble() / totalCount
    }

    fun bigramCount(prev: String, word: String): Long {
        val p = viId(normalize(prev))
        val w = viId(normalize(word))
        if (p < 0 || w < 0) return 0L
        val index = bigramKeys.binarySearch(pack(p, w))
        return if (index >= 0) bigramValues[index] else 0L
    }

    fun bigramTotal(prev: String): Long {
        val p = viId(normalize(prev))
        return if (p >= 0) bigramTotals[p] else 0L
    }

    private fun viId(normalized: String): Int =
        viIds[normalized] ?: viIds[ViText.withToneStyle(normalized, ToneStyle.MODERN)] ?: -1

    companion object {
        const val ENGLISH_WEIGHT = 0.3
        const val ENGLISH_TOP_N = 2000
        private val ENGLISH_WORD = Regex("[a-z]+")

        private fun normalize(word: String): String =
            Normalizer.normalize(word, Normalizer.Form.NFC).lowercase()

        private fun pack(prev: Int, word: Int): Long = (prev.toLong() shl 32) or word.toLong()

        /**
         * Parses `U\t<syllable>\t<count>` and `B\t<prev>\t<syllable>\t<count>` lines (unigrams first)
         * and combines them with the top English words. Malformed lines are skipped.
         */
        fun parse(lines: Sequence<String>, english: Map<String, Int>): ViGlideLexicon {
            val words = ArrayList<String>()
            var counts = LongArray(16384)
            val ids = HashMap<String, Int>()
            val rawToId = HashMap<String, Int>()
            val bigrams = BigramBuffer()

            for (line in lines) {
                val t1 = line.indexOf('\t')
                if (t1 != 1) continue
                val t2 = line.indexOf('\t', t1 + 1)
                if (t2 < 0) continue
                when (line[0]) {
                    'U' -> {
                        val count = line.substring(t2 + 1).toLongOrNull() ?: continue
                        counts = addUnigram(line.substring(t1 + 1, t2), count, words, counts, ids, rawToId)
                    }
                    'B' -> {
                        val t3 = line.indexOf('\t', t2 + 1)
                        if (t3 < 0) continue
                        val count = line.substring(t3 + 1).toLongOrNull() ?: continue
                        val p = lookup(line.substring(t1 + 1, t2), ids, rawToId)
                        val w = lookup(line.substring(t2 + 1, t3), ids, rawToId)
                        if (p >= 0 && w >= 0 && count > 0) bigrams.add(p, w, count)
                    }
                }
            }
            return build(words, counts.copyOf(words.size), ids, bigrams, english)
        }

        /** Growable primitive (prev, word, count) rows. */
        private class BigramBuffer {
            var prev = IntArray(1 shl 16)
            var word = IntArray(1 shl 16)
            var count = LongArray(1 shl 16)
            var size = 0

            fun add(p: Int, w: Int, c: Long) {
                if (size == prev.size) {
                    prev = prev.copyOf(size * 2)
                    word = word.copyOf(size * 2)
                    count = count.copyOf(size * 2)
                }
                prev[size] = p
                word[size] = w
                count[size] = c
                size++
            }
        }

        private fun addUnigram(
            raw: String,
            count: Long,
            words: MutableList<String>,
            counts: LongArray,
            ids: MutableMap<String, Int>,
            rawToId: MutableMap<String, Int>,
        ): LongArray {
            if (count <= 0) return counts
            val s = normalize(raw)
            if (!ViSyllable.isValid(s)) return counts
            val canonical = ViText.withToneStyle(s, ToneStyle.MODERN)
            val existing = ids[canonical]
            val id: Int
            var result = counts
            if (existing != null) {
                counts[existing] += count
                id = existing
            } else {
                id = words.size
                if (id == counts.size) result = counts.copyOf(id * 2)
                words.add(canonical)
                result[id] = count
                ids[canonical] = id
            }
            rawToId[raw] = id
            return result
        }

        private fun lookup(raw: String, ids: Map<String, Int>, rawToId: MutableMap<String, Int>): Int {
            rawToId[raw]?.let { return it }
            val s = normalize(raw)
            val id = ids[ViText.withToneStyle(s, ToneStyle.MODERN)] ?: -1
            if (id >= 0) rawToId[raw] = id
            return id
        }

        private fun build(
            words: List<String>,
            viCounts: LongArray,
            ids: Map<String, Int>,
            bigrams: BigramBuffer,
            english: Map<String, Int>,
        ): ViGlideLexicon {
            val maxVi = viCounts.maxOrNull() ?: 0L

            // An English word spelled like a Vietnamese syllable merges into that entry.
            val englishCounts = HashMap<String, Long>()
            english.entries
                .filter { ENGLISH_WORD.matches(it.key) }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(ENGLISH_TOP_N)
                .forEach { (w, freq) ->
                    val pseudo = (ENGLISH_WEIGHT * (freq / 255.0) * maxVi).roundToLong()
                    if (pseudo <= 0L) return@forEach
                    val viId = ids[w]
                    if (viId != null) viCounts[viId] += pseudo else englishCounts[w] = pseudo
                }

            val grouped = HashMap<String, MutableList<ViGlideEntry>>()
            words.forEachIndexed { i, w ->
                grouped.getOrPut(ViText.fold(w)) { ArrayList(2) }.add(ViGlideEntry(w, viCounts[i], false))
            }
            englishCounts.forEach { (w, c) ->
                grouped.getOrPut(w) { ArrayList(2) }.add(ViGlideEntry(w, c, true))
            }
            val byFolded = grouped.mapValues { (_, list) ->
                list.sortedWith(compareByDescending<ViGlideEntry> { it.count }.thenBy { it.word })
            }

            val n = bigrams.size
            require(words.size < (1 shl 21) && n < (1 shl 21)) { "lexicon too large" }
            val sortKeys = LongArray(n) { i ->
                (bigrams.prev[i].toLong() shl 42) or (bigrams.word[i].toLong() shl 21) or i.toLong()
            }
            sortKeys.sort()
            var keys = LongArray(n)
            var values = LongArray(n)
            var size = 0
            val rowMask = (1L shl 21) - 1
            for (sk in sortKeys) {
                val row = (sk and rowMask).toInt()
                val key = pack(bigrams.prev[row], bigrams.word[row])
                if (size > 0 && keys[size - 1] == key) {
                    values[size - 1] += bigrams.count[row]
                } else {
                    keys[size] = key
                    values[size] = bigrams.count[row]
                    size++
                }
            }
            keys = keys.copyOf(size)
            values = values.copyOf(size)
            val totals = LongArray(words.size)
            for (i in 0 until size) totals[(keys[i] ushr 32).toInt()] += values[i]

            return ViGlideLexicon(
                byFolded = byFolded,
                viIds = ids,
                viCounts = viCounts,
                englishCounts = englishCounts,
                bigramKeys = keys,
                bigramValues = values,
                bigramTotals = totals,
                totalCount = viCounts.sum() + englishCounts.values.sum(),
            )
        }
    }
}
