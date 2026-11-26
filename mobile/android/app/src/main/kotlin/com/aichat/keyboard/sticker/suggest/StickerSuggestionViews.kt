/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.suggest

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aichat.keyboard.sticker.BuiltInStickers
import com.aichat.keyboard.sticker.Sticker
import com.aichat.keyboard.sticker.StickerPack
import com.aichat.keyboard.sticker.StickerRepository
import com.aichat.keyboard.sticker.StickerSendFlow
import com.aichat.keyboard.sticker.StickerTag
import com.aichat.keyboard.sticker.ui.StickerThumbnails
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.smartbar.CandidatesRow
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.observeAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stickers to show for the words just before the cursor, and the text a sent sticker replaces. */
class TypingStickerSuggestion(val trigger: String, val stickers: List<Sticker>)

/**
 * The sticker suggestion for the focused field right now, or null: off in settings, a password / raw (TYPE_NULL)
 * field, incognito mode, a selection, no keyword before the cursor, or no sticker with a matching tag. Recomputed
 * on every editor content change; the matching is pure and cheap.
 */
@Composable
fun rememberTypingStickerSuggestion(): TypingStickerSuggestion? {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val enabled by prefs.aichatStickerSuggest.enabled.observeAsState()
    val editorInstance by context.editorInstance()
    val keyboardManager by context.keyboardManager()
    val content by editorInstance.activeContentFlow.collectAsState()
    val keyboardState by keyboardManager.activeState.collectAsState()
    val packs = rememberSuggestionPacks()

    // activeInfo is replaced on every new field, which also replaces the content, so reading it here stays current.
    val info = editorInstance.activeInfo
    val allowed = StickerSuggestLogic.isAllowed(
        enabled = enabled,
        isRawInputEditor = info.isRawInputEditor,
        isPasswordField = info.inputAttributes.variation in PASSWORD_VARIATIONS,
        isIncognito = keyboardState.isIncognitoMode,
    ) && !content.selection.isSelectionMode
    val before = if (allowed) content.textBeforeSelection else ""
    val after = if (allowed) content.textAfterSelection else ""
    return remember(allowed, before, after, packs) {
        if (!allowed || packs == null) return@remember null
        val match = StickerKeywordMatcher.match(before, after) ?: return@remember null
        val stickers = StickerSuggestLogic.pick(match.tags, packs)
        if (stickers.isEmpty()) null else TypingStickerSuggestion(match.trigger, stickers)
    }
}

private val PASSWORD_VARIATIONS = setOf(
    InputAttributes.Variation.PASSWORD,
    InputAttributes.Variation.VISIBLE_PASSWORD,
    InputAttributes.Variation.WEB_PASSWORD,
)

/**
 * The smartbar's word suggestions with up to three sticker thumbnails at the trailing end while a keyword is
 * before the cursor. Tapping one sends it and, if it went through, removes the keyword it replaces.
 */
@Composable
fun RowScope.CandidatesWithStickerSuggestions() {
    val suggestion = rememberTypingStickerSuggestion()
    CandidatesRow(modifier = Modifier.weight(1f))
    if (suggestion != null) {
        val context = LocalContext.current
        val editorInstance by context.editorInstance()
        StickerSuggestionThumbnails(
            stickers = suggestion.stickers,
            height = FlorisImeSizing.smartbarHeight,
            onSent = { deleteTriggerIfStillBeforeCursor(editorInstance, suggestion.trigger) },
        )
    }
}

/** Up to three thumbnails for the AI reply panel, from the backend's `sticker_tags`; nothing when none match. */
@Composable
fun AiReplyStickerSuggestions(tags: List<StickerTag>, height: Dp) {
    if (tags.isEmpty()) return
    val packs = rememberSuggestionPacks() ?: return
    val stickers = remember(tags, packs) { StickerSuggestLogic.pick(tags, packs) }
    if (stickers.isNotEmpty()) StickerSuggestionThumbnails(stickers = stickers, height = height, onSent = {})
}

/**
 * A row of small tappable sticker thumbnails, [height] tall. Sends through [StickerSendFlow] (one at a time) and
 * calls [onSent] after a successful send.
 */
@Composable
private fun StickerSuggestionThumbnails(stickers: List<Sticker>, height: Dp, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxHeight().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (sticker in stickers) {
            val description = stringResource(R.string.aichat__sticker_suggest__send, sticker.description)
            Box(
                modifier = Modifier
                    .size(height)
                    .padding(THUMB_PADDING)
                    .clip(RoundedCornerShape(THUMB_CORNER))
                    .clickable {
                        if (sending) return@clickable
                        sending = true
                        scope.launch {
                            try {
                                val result = StickerSendFlow.sendAndReport(context, sticker)
                                if (StickerSuggestLogic.isSuccess(result.name)) onSent()
                            } finally {
                                sending = false
                            }
                        }
                    }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                StickerSuggestionThumbnail(sticker, Modifier.fillMaxSize().padding(2.dp))
            }
        }
    }
}

@Composable
private fun StickerSuggestionThumbnail(sticker: Sticker, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(sticker.id) { mutableStateOf(StickerThumbnails.cached(sticker)) }
    LaunchedEffect(sticker.id) {
        if (bitmap == null) bitmap = StickerThumbnails.load(context, sticker)
    }
    bitmap?.let { Image(bitmap = it, contentDescription = null, modifier = modifier) }
}

private val THUMB_PADDING = 3.dp
private val THUMB_CORNER = 6.dp

@Composable
private fun rememberSuggestionPacks(): List<StickerPack>? {
    val context = LocalContext.current
    val packs by produceState(SuggestionPacks.cached) {
        if (value == null) value = SuggestionPacks.load(context)
    }
    return packs
}

/**
 * The packs suggestions draw from, in priority order: built-in first, then the bundled image packs. User stickers
 * have no tags, so they never match. Loaded once per process, off the main thread (asset packs read the APK).
 */
private object SuggestionPacks {
    private const val TAG = "StickerSuggest"

    @Volatile
    var cached: List<StickerPack>? = null
        private set

    suspend fun load(context: Context): List<StickerPack> = cached ?: withContext(Dispatchers.IO) {
        val assetPacks = try {
            StickerRepository.get(context).assetPacks
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load asset sticker packs", e)
            emptyList()
        }
        (BuiltInStickers.packs + assetPacks).distinctBy { it.id }
    }.also { cached = it }
}

/**
 * After a sticker from a typing suggestion was sent: deletes [trigger] if it is still exactly before the cursor
 * ([StickerSuggestLogic.charsToDelete]); otherwise leaves the text alone.
 */
private fun deleteTriggerIfStillBeforeCursor(editorInstance: EditorInstance, trigger: String) {
    val ic = FlorisImeService.currentInputConnection() ?: return
    // Commit the composed vi-input word first, so the text read below is what is really in the field.
    editorInstance.viInput.finishWord()
    ic.beginBatchEdit()
    try {
        ic.finishComposingText()
        val before = ic.getTextBeforeCursor(trigger.length + 1, 0)?.toString() ?: return
        val hasSelection = !ic.getSelectedText(0).isNullOrEmpty()
        val count = StickerSuggestLogic.charsToDelete(trigger, before, hasSelection)
        if (count > 0) {
            editorInstance.viInput.reset()
            ic.deleteSurroundingText(count, 0)
        }
    } finally {
        ic.endBatchEdit()
    }
}
