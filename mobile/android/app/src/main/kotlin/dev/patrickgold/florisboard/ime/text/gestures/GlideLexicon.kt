/*
 * Copyright (C) 2026 AIChat
 */

package dev.patrickgold.florisboard.ime.text.gestures

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.NlpManager

/**
 * Word source of [StatisticalGlideTypingClassifier]: the words a swipe is matched against and their
 * frequency in [0, 1]. Equality is the cache identity (the classifier caches its pruner per subtype
 * and lexicon), so implementations define it on their data source.
 */
interface GlideLexicon {
    val words: List<String>

    fun frequency(word: String): Double
}

/**
 * Upstream FlorisBoard word source: the subtype's NLP provider. Equal by [subtype], so the pruner cache
 * behaves exactly as when the classifier was keyed by subtype alone.
 */
class NlpGlideLexicon(private val nlpManager: NlpManager, val subtype: Subtype) : GlideLexicon {
    override val words: List<String> by lazy { nlpManager.getListOfWords(subtype) }

    override fun frequency(word: String): Double = nlpManager.getFrequencyForWord(subtype, word)

    override fun equals(other: Any?): Boolean = other is NlpGlideLexicon && other.subtype == subtype

    override fun hashCode(): Int = subtype.hashCode()
}

/** No words: swipes match nothing. Used while a subtype's own lexicon is still loading. */
object EmptyGlideLexicon : GlideLexicon {
    override val words: List<String> = emptyList()

    override fun frequency(word: String): Double = 0.0
}
