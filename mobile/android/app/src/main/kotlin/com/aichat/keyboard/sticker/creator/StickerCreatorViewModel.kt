/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads the photo to turn into a sticker (upright, longer side ≤ [CutoutSpec.MAX_INPUT_PX]); null if unreadable. */
typealias StickerImageLoader = suspend () -> StickerPixels?

/** The cutout and effect steps, behind an interface so the view model is tested with fakes. */
interface StickerCutoutEngine {
    fun subjectAt(result: SegmentationResult, x: Int, y: Int): Int?

    fun compose(source: StickerPixels, result: SegmentationResult, selected: Set<Int>): StickerPixels

    fun applyEffect(effect: StickerEffect, cutout: StickerPixels): StickerPixels

    /** The real [CutoutComposer] and [StickerEffects]. */
    companion object Default : StickerCutoutEngine {
        override fun subjectAt(result: SegmentationResult, x: Int, y: Int): Int? = CutoutComposer.subjectAt(result, x, y)

        override fun compose(source: StickerPixels, result: SegmentationResult, selected: Set<Int>): StickerPixels =
            CutoutComposer.compose(source, result, selected)

        override fun applyEffect(effect: StickerEffect, cutout: StickerPixels): StickerPixels =
            StickerEffects.apply(effect, cutout)
    }
}

/**
 * The "create sticker from photo" flow (iOS-style):
 * [State.CheckingModel] → ([State.Unsupported] | [State.Downloading] →) [State.Ready] → [State.Decoding] →
 * [State.Segmenting] → [State.Selecting] → [State.Preview] → [State.Saving] → [State.Saved], with [State.Error] from
 * any step. Everything runs on the device; heavy work runs on [worker] and is cancelled with the view model.
 */
class StickerCreatorViewModel(
    private val segmenter: SubjectSegmenter,
    private val store: StickerCreatorStore,
    private val engine: StickerCutoutEngine = StickerCutoutEngine.Default,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    enum class ErrorKind {
        /** The segmentation model could not be downloaded (usually no network). Retryable. */
        DOWNLOAD_FAILED,

        /** The photo could not be opened. The user can pick another one. */
        UNREADABLE_IMAGE,

        /** Nothing stands out in the photo. The user can use the whole photo or pick another one. */
        NO_SUBJECTS,

        /** Segmentation failed. Retryable. */
        SEGMENT_FAILED,

        /** Cutting out or applying the effect failed. Retryable. */
        RENDER_FAILED,

        /** "My stickers" already holds the maximum. */
        STORE_FULL,

        /** Writing the sticker failed. Retryable. */
        SAVE_FAILED,
    }

    sealed interface State {
        data object CheckingModel : State

        /** No subject segmentation on this device; plain gallery import stays available in the keyboard. */
        data object Unsupported : State

        /** One-time model download through Google Play services (no progress is reported). */
        data object Downloading : State

        /** The model is ready and a photo is needed (the host opens the photo picker, see [claimPhotoPicker]). */
        data object Ready : State

        data object Decoding : State

        data object Segmenting : State

        /**
         * The subject picker. [overlay] is [SelectionOverlay.render] (stretch it over [image]), [lifted] the selected
         * subjects to draw lifted off the photo.
         */
        data class Selecting(
            val image: StickerPixels,
            val subjectCount: Int,
            val selected: Set<Int>,
            val overlay: StickerPixels,
            val lifted: LiftedLayer?,
        ) : State

        /** [sticker] is null only while the first preview renders; [rendering] while [effect] is being applied. */
        data class Preview(
            val effect: StickerEffect,
            val sticker: StickerPixels?,
            val rendering: Boolean,
            val wholePhoto: Boolean,
        ) : State

        data object Saving : State

        data object Saved : State

        data class Error(val kind: ErrorKind, val retryable: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.CheckingModel)
    val state: StateFlow<State> = _state.asStateFlow()

    private var started = false
    private var modelReady = false
    private var pendingImage: StickerImageLoader? = null
    private var pickerOpen = false

    private var source: StickerPixels? = null
    private var segmentation: SegmentationResult? = null
    private var selected: Set<Int> = emptySet()
    private var cutout: StickerPixels? = null
    private var wholePhoto = false
    private var effect = DEFAULT_EFFECT
    private val previews = mutableMapOf<StickerEffect, StickerPixels>()

    private var job: Job? = null
    private var retryAction: (() -> Unit)? = null

    /** Checks the model, then processes [initialImage] (or waits in [State.Ready]). Only the first call does anything. */
    fun start(initialImage: StickerImageLoader?) {
        if (started) return
        started = true
        pendingImage = initialImage
        launchStep { checkModel() }
    }

    /** The photo to use, e.g. from the picker. Kept until the model is ready. */
    fun open(image: StickerImageLoader) {
        pickerOpen = false
        if (!modelReady) {
            pendingImage = image
            return
        }
        launchStep { process(image) }
    }

    /** True once per [State.Ready]: the host should open the photo picker now (not again after a rotation). */
    fun claimPhotoPicker(): Boolean {
        if (_state.value != State.Ready || pickerOpen) return false
        pickerOpen = true
        return true
    }

    /** The picker was closed without a photo. */
    fun photoPickerCancelled() {
        pickerOpen = false
    }

    /** Back to [State.Ready] for another photo (the host opens the picker again). */
    fun pickAnother() {
        job?.cancel()
        clearPhoto()
        pickerOpen = false
        _state.value = if (modelReady) State.Ready else State.CheckingModel
    }

    /** A tap on the photo at source image pixel ([x], [y]): toggles the subject there, if any. */
    fun tap(x: Int, y: Int) {
        val result = segmentation ?: return
        if (_state.value !is State.Selecting) return
        val index = engine.subjectAt(result, x, y) ?: return
        toggle(index)
    }

    /** Toggles subject [index] (also the accessibility action of each subject). */
    fun toggle(index: Int) {
        val result = segmentation ?: return
        if (_state.value !is State.Selecting || index !in result.subjects.indices) return
        select(if (index in selected) selected - index else selected + index)
    }

    fun selectAll() {
        val result = segmentation ?: return
        if (_state.value !is State.Selecting) return
        select(result.subjects.indices.toSet())
    }

    /** Cuts out the selected subjects and shows the effect preview. */
    fun confirmSelection() {
        val image = source ?: return
        val result = segmentation ?: return
        if (_state.value !is State.Selecting || selected.isEmpty()) return
        val chosen = selected
        launchStep { makeCutout(wholePhoto = false) { engine.compose(image, result, chosen) } }
    }

    /** When no subject was found: the whole photo, fitted into the sticker square. */
    fun useWholePhoto() {
        val image = source ?: return
        launchStep { makeCutout(wholePhoto = true) { StickerCreatorMath.fitIntoSquare(image) } }
    }

    fun selectEffect(next: StickerEffect) {
        val current = _state.value as? State.Preview ?: return
        if (next == current.effect && current.sticker != null && !current.rendering) return
        effect = next
        val base = cutout ?: return
        launchStep { render(base, current.sticker) }
    }

    fun save() {
        val current = _state.value as? State.Preview ?: return
        val sticker = current.sticker ?: return
        if (current.rendering) return
        launchStep { saveSticker(sticker) }
    }

    /** Repeats the step that failed. */
    fun retry() {
        val action = retryAction ?: return
        retryAction = null
        action()
    }

    /** Back from the preview to the subject picker. False when there is no step to go back to (close the screen). */
    fun back(): Boolean {
        val preview = _state.value as? State.Preview ?: return false
        job?.cancel()
        if (preview.wholePhoto) {
            _state.value = State.Error(ErrorKind.NO_SUBJECTS, retryable = false)
        } else {
            launchStep { showSelection(selected) }
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        segmenter.close()
    }

    private suspend fun checkModel() {
        _state.value = State.CheckingModel
        if (runCatchingWork { store.isFull() } == true) {
            fail(ErrorKind.STORE_FULL, retry = null)
            return
        }
        when (runCatchingWork { segmenter.availability() } ?: SegmenterAvailability.Unsupported) {
            SegmenterAvailability.Unsupported -> _state.value = State.Unsupported
            SegmenterAvailability.Ready -> onModelReady()
            SegmenterAvailability.NeedsDownload -> download()
        }
    }

    private suspend fun download() {
        _state.value = State.Downloading
        if (runCatchingWork { segmenter.ensureReady() } == true) {
            onModelReady()
        } else {
            fail(ErrorKind.DOWNLOAD_FAILED) { launchStep { download() } }
        }
    }

    private suspend fun onModelReady() {
        modelReady = true
        val image = pendingImage
        pendingImage = null
        if (image == null) _state.value = State.Ready else process(image)
    }

    private suspend fun process(image: StickerImageLoader) {
        clearPhoto()
        _state.value = State.Decoding
        val decoded = runCatchingWork { withContext(worker) { image() } }
        if (decoded == null) {
            fail(ErrorKind.UNREADABLE_IMAGE, retry = null)
            return
        }
        source = decoded
        segment(decoded)
    }

    private suspend fun segment(image: StickerPixels) {
        _state.value = State.Segmenting
        val result = runCatchingWork { withContext(worker) { segmenter.segment(image) } }
        if (result == null) {
            fail(ErrorKind.SEGMENT_FAILED) { launchStep { segment(image) } }
            return
        }
        segmentation = result
        if (result.subjects.isEmpty()) {
            fail(ErrorKind.NO_SUBJECTS, retry = null)
            return
        }
        // Like iOS, every subject starts lifted; a tap drops the ones the user does not want.
        showSelection(result.subjects.indices.toSet())
    }

    private fun select(next: Set<Int>) {
        launchStep { showSelection(next) }
    }

    private suspend fun showSelection(next: Set<Int>) {
        val image = source ?: return
        val result = segmentation ?: return
        selected = next
        val layers = runCatchingWork {
            withContext(worker) {
                SelectionOverlay.render(result, next) to SelectionOverlay.lifted(image, result, next)
            }
        }
        if (layers == null) {
            fail(ErrorKind.RENDER_FAILED) { launchStep { showSelection(next) } }
            return
        }
        _state.value = State.Selecting(image, result.subjects.size, next, layers.first, layers.second)
    }

    private suspend fun makeCutout(wholePhoto: Boolean, make: () -> StickerPixels) {
        val previous = (_state.value as? State.Preview)?.sticker
        _state.value = State.Preview(effect, previous, rendering = true, wholePhoto = wholePhoto)
        val made = runCatchingWork { withContext(worker) { make() } }
        if (made == null) {
            fail(ErrorKind.RENDER_FAILED) { launchStep { makeCutout(wholePhoto, make) } }
            return
        }
        this.wholePhoto = wholePhoto
        cutout = made
        previews.clear()
        render(made, previous = null)
    }

    private suspend fun render(base: StickerPixels, previous: StickerPixels?) {
        val target = effect
        previews[target]?.let { cached ->
            _state.value = State.Preview(target, cached, rendering = false, wholePhoto = wholePhoto)
            return
        }
        _state.value = State.Preview(target, previous, rendering = true, wholePhoto = wholePhoto)
        val rendered = if (target == StickerEffect.ORIGINAL) {
            base
        } else {
            runCatchingWork { withContext(worker) { engine.applyEffect(target, base) } }
        }
        if (rendered == null) {
            fail(ErrorKind.RENDER_FAILED) { launchStep { render(base, previous) } }
            return
        }
        previews[target] = rendered
        _state.value = State.Preview(target, rendered, rendering = false, wholePhoto = wholePhoto)
    }

    private suspend fun saveSticker(sticker: StickerPixels) {
        _state.value = State.Saving
        when (runCatchingWork { store.save(sticker) }) {
            StickerCreatorStore.SaveOutcome.ADDED -> {
                // The sticker is stored; a failed refresh only delays it showing up in the palette.
                runCatchingWork { store.refresh() }
                _state.value = State.Saved
            }
            StickerCreatorStore.SaveOutcome.FULL -> fail(ErrorKind.STORE_FULL, retry = null)
            null -> fail(ErrorKind.SAVE_FAILED) { launchStep { saveSticker(sticker) } }
        }
    }

    private fun fail(kind: ErrorKind, retry: (() -> Unit)?) {
        retryAction = retry
        _state.value = State.Error(kind, retryable = retry != null)
    }

    private fun clearPhoto() {
        source = null
        segmentation = null
        selected = emptySet()
        cutout = null
        wholePhoto = false
        previews.clear()
    }

    /** Runs one step of the flow; a new step cancels the one still running. */
    private fun launchStep(block: suspend () -> Unit) {
        job?.cancel()
        retryAction = null
        job = viewModelScope.launch { block() }
    }

    /** [block]'s result, or null when it failed (never swallows cancellation). */
    private suspend fun <T> runCatchingWork(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    companion object {
        /** The die-cut white border reads as a sticker in any chat, so the preview starts there. */
        val DEFAULT_EFFECT = StickerEffect.OUTLINE

        /** Effect chips, in this order. */
        val EFFECTS = listOf(
            StickerEffect.ORIGINAL,
            StickerEffect.OUTLINE,
            StickerEffect.COMIC,
            StickerEffect.PUFFY,
            StickerEffect.SHINY,
        )
    }
}
