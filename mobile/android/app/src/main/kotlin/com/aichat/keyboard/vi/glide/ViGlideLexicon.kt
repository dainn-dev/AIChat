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
            val counts = ArrayList<Long>()
            val ids = HashMap<String, Int>()
            val rawToId = HashMap<String, Int>()
            val packed = ArrayList<Long>()
            val bigramCounts = ArrayList<Long>()

            for (line in lines) {
                val f = line.split('\t')
                when {
                    f.size == 3 && f[0] == "U" -> addUnigram(f[1], f[2], words, counts, ids, rawToId)
                    f.size == 4 && f[0] == "B" -> {
                        val count = f[3].toLongOrNull() ?: continue
                        val p = lookup(f[1], ids, rawToId)
                        val w = lookup(f[2], ids, rawToId)
                        if (p >= 0 && w >= 0 && count > 0) {
                            packed.add(pack(p, w))
                            bigramCounts.add(count)
                        }
                    }
                }
            }
            return build(words, counts, ids, packed, bigramCounts, english)
        }

        private fun addUnigram(
            raw: String,
            rawCount: String,
            words: MutableList<String>,
            counts: MutableList<Long>,
            ids: MutableMap<String, Int>,
            rawToId: MutableMap<String, Int>,
        ) {
            val count = rawCount.toLongOrNull() ?: return
            if (count <= 0) return
            val s = normalize(raw)
            if (!ViSyllable.isValid(s)) return
            val canonical = ViText.withToneStyle(s, ToneStyle.MODERN)
            val existing = ids[canonical]
            val id = if (existing != null) {
                counts[existing] = counts[existing] + count
                existing
            } else {
                words.add(canonical)
                counts.add(count)
                ids[canonical] = words.size - 1
                words.size - 1
            }
            rawToId[raw] = id
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
            counts: List<Long>,
            ids: Map<String, Int>,
            packed: List<Long>,
            bigramCounts: List<Long>,
            english: Map<String, Int>,
        ): ViGlideLexicon {
            val viCounts = counts.toLongArray()
            val maxVi = viCounts.maxOrNull() ?: 0L

            val englishCounts = HashMap<String, Long>()
            english.entries
                .filter { ENGLISH_WORD.matches(it.key) }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(ENGLISH_TOP_N)
                .forEach { (w, freq) ->
                    englishCounts[w] = (ENGLISH_WEIGHT * (freq / 255.0) * maxVi).roundToLong()
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

            val order = packed.indices.sortedBy { packed[it] }
            val keys = ArrayList<Long>(order.size)
            val values = ArrayList<Long>(order.size)
            for (i in order) {
                if (keys.isNotEmpty() && keys.last() == packed[i]) {
                    values[values.lastIndex] = values.last() + bigramCounts[i]
                } else {
                    keys.add(packed[i])
                    values.add(bigramCounts[i])
                }
            }
            val totals = LongArray(words.size)
            keys.forEachIndexed { i, k -> totals[(k ushr 32).toInt()] += values[i] }

            return ViGlideLexicon(
                byFolded = byFolded,
                viIds = ids,
                viCounts = viCounts,
                englishCounts = englishCounts,
                bigramKeys = keys.toLongArray(),
                bigramValues = values.toLongArray(),
                bigramTotals = totals,
                totalCount = viCounts.sum() + englishCounts.values.sum(),
            )
        }
    }
}
