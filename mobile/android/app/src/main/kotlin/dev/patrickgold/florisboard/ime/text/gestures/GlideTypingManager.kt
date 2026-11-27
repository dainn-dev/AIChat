/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.gestures

import android.content.Context
import com.aichat.keyboard.vi.ViInputComposer
import com.aichat.keyboard.vi.glide.PreviousSyllable
import com.aichat.keyboard.vi.glide.ViGlideClassifierLexicon
import com.aichat.keyboard.vi.glide.ViGlideLexicon
import com.aichat.keyboard.vi.glide.ViGlideLexiconLoader
import com.aichat.keyboard.vi.glide.ViGlideRanker
import com.aichat.keyboard.vi.glide.ViGlideRouting
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Handles the [GlideTypingClassifier]. Basically responsible for linking [GlideTypingGesture.Detector]
 * with [GlideTypingClassifier].
 */
class GlideTypingManager(private val context: Context) : GlideTypingGesture.Listener { // AIChat MODIFIED: keeps context for the lexicon loader.
    companion object {
        private const val MAX_SUGGESTION_COUNT = 8
    }

    private val prefs by FlorisPreferenceStore
    private val keyboardManager by context.keyboardManager()
    private val nlpManager by context.nlpManager()
    private val subtypeManager by context.subtypeManager()
    private val editorInstance by context.editorInstance() // AIChat MODIFIED: text before the cursor, tone style.

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var glideTypingClassifier = StatisticalGlideTypingClassifier(context)
    private var lastTime = System.currentTimeMillis()

    // AIChat MODIFIED: Vietnamese glide state. Main thread only.
    private var lastKeys: List<TextKey> = emptyList()
    private var viLexiconLoad: Job? = null

    /** AIChat MODIFIED: the classifier word source for [subtype]; null on Vietnamese while the lexicon loads. */
    private sealed interface Route {
        val lexicon: GlideLexicon

        class Default(override val lexicon: GlideLexicon) : Route

        class Vietnamese(val vi: ViGlideLexicon?) : Route {
            override val lexicon: GlideLexicon = vi?.let { ViGlideClassifierLexicon(it) } ?: EmptyGlideLexicon
        }
    }

    // AIChat MODIFIED: Vietnamese is chosen by the subtype's composer, the same check as ViInputGate.
    private fun routeFor(subtype: Subtype): Route {
        val composer = keyboardManager.resources.composers.value?.get(subtype.composer)
        return if (ViGlideRouting.useVietnamese(ViInputComposer.isViInput(subtype.composer, composer))) {
            Route.Vietnamese(ViGlideLexiconLoader.peek())
        } else {
            Route.Default(NlpGlideLexicon(nlpManager, subtype))
        }
    }

    // AIChat MODIFIED: loads the Vietnamese lexicon off the main thread, then points the classifier at it.
    private fun ensureViLexiconLoading() {
        if (viLexiconLoad?.isActive == true) return
        viLexiconLoad = scope.launch(Dispatchers.Main) {
            try {
                ViGlideLexiconLoader.get(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch // the loader logs the failure (no text); the next glide retries
            }
            val subtype = subtypeManager.activeSubtype
            val route = routeFor(subtype)
            if (route is Route.Vietnamese && lastKeys.isNotEmpty()) {
                glideTypingClassifier.setLayout(lastKeys, subtype, route.lexicon)
            }
        }
    }

    override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
        updateSuggestionsAsync(MAX_SUGGESTION_COUNT, true) {
            glideTypingClassifier.clear()
        }
    }

    override fun onGlideCancelled() {
        glideTypingClassifier.clear()
    }

    override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
        val normalized = GlideTypingGesture.Detector.Position(point.x, point.y)

        this.glideTypingClassifier.addGesturePoint(normalized)

        val time = System.currentTimeMillis()
        if (prefs.glide.showPreview.get() && time - lastTime > prefs.glide.previewRefreshDelay.get()) {
            updateSuggestionsAsync(1, false) {}
            lastTime = time
        }
    }

    /**
     * Change the layout of the internal gesture classifier
     */
    fun setLayout(keys: List<TextKey>) {
        if (keys.isNotEmpty()) {
            // AIChat MODIFIED: word source by route; the Vietnamese lexicon starts loading with the keyboard.
            val subtype = subtypeManager.activeSubtype
            val route = routeFor(subtype)
            lastKeys = keys
            if (route is Route.Vietnamese && route.vi == null) ensureViLexiconLoading()
            glideTypingClassifier.setLayout(keys, subtype, route.lexicon)
        }
    }

    /**
     * Asks gesture classifier for suggestions and then passes that on to the smartbar.
     * Also commits the most confident suggestion if [commit] is set. All happens on an async executor.
     * NB: only fetches [MAX_SUGGESTION_COUNT] suggestions.
     *
     * @param callback Called when this function completes. Takes a boolean, which indicates if suggestions
     * were successfully set.
     */
    private fun updateSuggestionsAsync(maxSuggestionsToShow: Int, commit: Boolean, callback: (Boolean) -> Unit) {
        // AIChat MODIFIED: route per active subtype. Vietnamese before its lexicon loads does nothing (no
        // English fallback); a classifier built for another subtype or word source is not ready.
        val subtype = subtypeManager.activeSubtype
        val route = routeFor(subtype)
        if (route is Route.Vietnamese && route.vi == null) {
            ensureViLexiconLoading()
            callback.invoke(false)
            return
        }
        if (!glideTypingClassifier.isReadyFor(subtype, route.lexicon)) {
            callback.invoke(false)
            return
        }
        if (route is Route.Vietnamese) {
            updateVietnameseSuggestionsAsync(route.vi!!, maxSuggestionsToShow, commit, callback)
            return
        }

        scope.launch(Dispatchers.Default) {
            val suggestions = glideTypingClassifier.getSuggestions(MAX_SUGGESTION_COUNT, true)

            withContext(Dispatchers.Main) {
                val suggestionList = buildList {
                    suggestions.subList(
                        1.coerceAtMost(min(commit.compareTo(false), suggestions.size)),
                        maxSuggestionsToShow.coerceAtMost(suggestions.size)
                    ).map { keyboardManager.fixCase(it) }.forEach {
                        add(WordSuggestionCandidate(it, confidence = 1.0))
                    }
                }

                nlpManager.suggestDirectly(suggestionList)
                if (commit && suggestions.isNotEmpty()) {
                    keyboardManager.commitGesture(suggestions.first())
                }
                callback.invoke(true)
            }
        }
    }

    /**
     * AIChat MODIFIED: Vietnamese counterpart of [updateSuggestionsAsync]. The classifier returns folded
     * forms; [ViGlideRanker] expands them into accented syllables using the previous syllable as context.
     * The first one is committed, the rest go to the smartbar. Never logs any of the text.
     */
    private fun updateVietnameseSuggestionsAsync(
        lexicon: ViGlideLexicon,
        maxSuggestionsToShow: Int,
        commit: Boolean,
        callback: (Boolean) -> Unit,
    ) {
        val textBefore = ViGlideRouting.previousTextWindow(editorInstance.activeContent.textBeforeSelection)
        val style = editorInstance.viInput.currentSettings().toneStyle

        scope.launch(Dispatchers.Default) {
            val folded = glideTypingClassifier.getSuggestions(MAX_SUGGESTION_COUNT, true)
            val previous = PreviousSyllable.of(textBefore, lexicon::isKnown)
            val ranked = ViGlideRanker.rank(folded, previous, lexicon, style)

            withContext(Dispatchers.Main) {
                val (word, rest) = ViGlideRouting.candidates(ranked, keyboardManager::fixCase)
                val shown = if (commit) rest else listOfNotNull(word).take(maxSuggestionsToShow)
                nlpManager.suggestDirectly(shown.map { WordSuggestionCandidate(it, confidence = 1.0) })
                if (commit && word != null) {
                    keyboardManager.commitGesture(word)
                }
                callback.invoke(true)
            }
        }
    }
}
