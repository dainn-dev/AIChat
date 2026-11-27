/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import com.aichat.vi.ToneStyle
import com.aichat.vi.ViText

/**
 * Expands the classifier's folded forms into real words and orders them by
 * `0.5^r * P(s | prev)`, where r is the folded form's classifier rank.
 */
object ViGlideRanker {
    const val K = 5
    const val MAX_RESULTS = 9
    const val SMOOTHING = 5.0
    private const val RANK_DECAY = 0.5

    private class Candidate(val score: Double, val rank: Int, val word: String)

    fun rank(
        foldedInOrder: List<String>,
        previous: String?,
        lexicon: ViGlideLexicon,
        style: ToneStyle,
    ): List<String> {
        val previousTotal = previous?.let { lexicon.bigramTotal(it) } ?: 0L
        val candidates = ArrayList<Candidate>()
        var decay = 1.0
        for ((rank, folded) in foldedInOrder.take(K).withIndex()) {
            for (entry in lexicon.expansions(folded)) {
                val probability = if (previous == null) {
                    lexicon.unigramProbability(entry.word)
                } else {
                    (lexicon.bigramCount(previous, entry.word) + SMOOTHING * lexicon.unigramProbability(entry.word)) /
                        (previousTotal + SMOOTHING)
                }
                val output = if (entry.english) entry.word else ViText.withToneStyle(entry.word, style)
                candidates.add(Candidate(decay * probability, rank, output))
            }
            decay *= RANK_DECAY
        }
        return candidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.rank }.thenBy { it.word })
            .map { it.word }
            .distinct()
            .take(MAX_RESULTS)
    }
}
