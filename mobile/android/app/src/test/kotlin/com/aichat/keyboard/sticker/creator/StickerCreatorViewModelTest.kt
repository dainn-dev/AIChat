package com.aichat.keyboard.sticker.creator

import androidx.lifecycle.viewModelScope
import com.aichat.keyboard.sticker.creator.StickerCreatorViewModel.ErrorKind
import com.aichat.keyboard.sticker.creator.StickerCreatorViewModel.State
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StickerCreatorViewModelTest {
    private val photo = StickerPixels(20, 10, IntArray(200) { 0xFF336699.toInt() })

    /** Two subjects: a box on the left (0) and one on the right (1). */
    private val twoSubjects = SegmentationResult(
        20,
        10,
        listOf(subject(left = 1, top = 1, width = 6, height = 8), subject(left = 12, top = 1, width = 6, height = 8)),
    )

    private class FakeSegmenter : SubjectSegmenter {
        var availability: SegmenterAvailability = SegmenterAvailability.Ready
        var download: suspend () -> Boolean = { true }
        var segment: suspend (StickerPixels) -> SegmentationResult = { error("no result set") }
        var segmentCalls = 0
        var closed = false

        override suspend fun availability() = availability
        override suspend fun ensureReady() = download()
        override suspend fun segment(source: StickerPixels): SegmentationResult {
            segmentCalls++
            return segment.invoke(source)
        }
        override fun close() {
            closed = true
        }
    }

    private class FakeEngine : StickerCutoutEngine {
        val composed = mutableListOf<Set<Int>>()
        val applied = mutableListOf<StickerEffect>()
        var failEffects = false

        override fun subjectAt(result: SegmentationResult, x: Int, y: Int): Int? =
            result.subjects.indexOfFirst { it.confidenceAt(x, y) >= CutoutSpec.MASK_THRESHOLD }.takeIf { it >= 0 }

        override fun compose(source: StickerPixels, result: SegmentationResult, selected: Set<Int>): StickerPixels {
            composed += selected
            return StickerPixels.transparent(8, 8)
        }

        override fun applyEffect(effect: StickerEffect, cutout: StickerPixels): StickerPixels {
            if (failEffects) throw IllegalStateException("effect failed")
            applied += effect
            return StickerPixels(cutout.width, cutout.height, IntArray(cutout.argb.size) { effect.ordinal })
        }
    }

    private class FakeStore : StickerCreatorStore {
        var full = false
        var outcome = StickerCreatorStore.SaveOutcome.ADDED
        var failSaves = 0
        val saved = mutableListOf<StickerPixels>()
        var refreshes = 0

        override suspend fun isFull() = full
        override suspend fun save(sticker: StickerPixels): StickerCreatorStore.SaveOutcome {
            if (failSaves > 0) {
                failSaves--
                throw IOException("disk full")
            }
            if (outcome == StickerCreatorStore.SaveOutcome.ADDED) saved += sticker
            return outcome
        }
        override suspend fun refresh() {
            refreshes++
        }
    }

    private val segmenter = FakeSegmenter().apply { segment = { twoSubjects } }
    private val engine = FakeEngine()
    private val store = FakeStore()
    private val created = mutableListOf<StickerCreatorViewModel>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun viewModel() = StickerCreatorViewModel(
        segmenter = segmenter,
        store = store,
        engine = engine,
        worker = Dispatchers.Main,
    ).also { created += it }

    private fun started(image: StickerImageLoader? = { photo }) = viewModel().apply { start(image) }

    @Test
    fun `unsupported device stops before any photo work`() {
        segmenter.availability = SegmenterAvailability.Unsupported
        var loads = 0
        val vm = started { loads++; photo }
        assertEquals(State.Unsupported, vm.state.value)
        assertEquals(0, loads)
        assertEquals(0, segmenter.segmentCalls)
    }

    @Test
    fun `a full sticker store is reported up front`() {
        store.full = true
        val vm = started()
        assertEquals(State.Error(ErrorKind.STORE_FULL, retryable = false), vm.state.value)
        assertEquals(0, segmenter.segmentCalls)
    }

    @Test
    fun `failed model download is retryable`() {
        segmenter.availability = SegmenterAvailability.NeedsDownload
        segmenter.download = { false }
        val vm = started(image = null)
        assertEquals(State.Error(ErrorKind.DOWNLOAD_FAILED, retryable = true), vm.state.value)

        segmenter.download = { true }
        vm.retry()
        assertEquals(State.Ready, vm.state.value)
    }

    @Test
    fun `shows the download while it runs, then processes the photo`() {
        segmenter.availability = SegmenterAvailability.NeedsDownload
        val gate = CompletableDeferred<Boolean>()
        segmenter.download = { gate.await() }
        val vm = started()
        assertEquals(State.Downloading, vm.state.value)

        gate.complete(true)
        val selecting = assertIs<State.Selecting>(vm.state.value)
        assertEquals(setOf(0, 1), selecting.selected)
        assertEquals(2, selecting.subjectCount)
    }

    @Test
    fun `a photo picked during the download waits for the model`() {
        segmenter.availability = SegmenterAvailability.NeedsDownload
        val gate = CompletableDeferred<Boolean>()
        segmenter.download = { gate.await() }
        val vm = started(image = null)
        vm.open { photo }
        assertEquals(State.Downloading, vm.state.value)

        gate.complete(true)
        assertIs<State.Selecting>(vm.state.value)
    }

    @Test
    fun `without a photo it waits and asks for the picker once`() {
        val vm = started(image = null)
        assertEquals(State.Ready, vm.state.value)
        assertTrue(vm.claimPhotoPicker())
        assertFalse(vm.claimPhotoPicker(), "no second picker after a rotation")

        vm.open { photo }
        assertIs<State.Selecting>(vm.state.value)
    }

    @Test
    fun `start runs only once`() {
        var loads = 0
        val vm = started { loads++; photo }
        vm.start { loads++; photo }
        assertEquals(1, loads)
    }

    @Test
    fun `unreadable photo offers another pick`() {
        val vm = started { null }
        assertEquals(State.Error(ErrorKind.UNREADABLE_IMAGE, retryable = false), vm.state.value)
        assertEquals(0, segmenter.segmentCalls)

        vm.pickAnother()
        assertEquals(State.Ready, vm.state.value)
        assertTrue(vm.claimPhotoPicker())
    }

    @Test
    fun `failed segmentation is retryable`() {
        segmenter.segment = { throw IllegalStateException("model crashed") }
        val vm = started()
        assertEquals(State.Error(ErrorKind.SEGMENT_FAILED, retryable = true), vm.state.value)

        segmenter.segment = { twoSubjects }
        vm.retry()
        assertIs<State.Selecting>(vm.state.value)
        assertEquals(2, segmenter.segmentCalls)
    }

    @Test
    fun `no subjects falls back to the whole photo in the sticker square`() {
        segmenter.segment = { SegmentationResult(20, 10, emptyList()) }
        val vm = started()
        assertEquals(State.Error(ErrorKind.NO_SUBJECTS, retryable = false), vm.state.value)

        vm.useWholePhoto()
        val preview = assertIs<State.Preview>(vm.state.value)
        assertTrue(preview.wholePhoto)
        assertEquals(StickerCreatorViewModel.DEFAULT_EFFECT, preview.effect)
        assertEquals(CutoutSpec.OUTPUT_PX, preview.sticker!!.width)
        assertEquals(CutoutSpec.OUTPUT_PX, preview.sticker!!.height)
        assertTrue(engine.composed.isEmpty(), "the whole photo is not a cutout")

        assertTrue(vm.back())
        assertEquals(State.Error(ErrorKind.NO_SUBJECTS, retryable = false), vm.state.value)
    }

    @Test
    fun `a tap toggles the subject under it and ignores the background`() {
        val vm = started()
        vm.tap(3, 4)
        assertEquals(setOf(1), assertIs<State.Selecting>(vm.state.value).selected)

        vm.tap(10, 5) // between the subjects
        assertEquals(setOf(1), assertIs<State.Selecting>(vm.state.value).selected)

        vm.tap(3, 4)
        assertEquals(setOf(0, 1), assertIs<State.Selecting>(vm.state.value).selected)

        vm.tap(3, 4)
        vm.tap(14, 4)
        val none = assertIs<State.Selecting>(vm.state.value)
        assertEquals(emptySet(), none.selected)
        assertEquals(null, none.lifted)

        vm.confirmSelection()
        assertIs<State.Selecting>(vm.state.value, "nothing to cut out")

        vm.selectAll()
        assertEquals(setOf(0, 1), assertIs<State.Selecting>(vm.state.value).selected)
    }

    @Test
    fun `the lifted layer covers the selected subjects`() {
        val vm = started()
        vm.toggle(0)
        val lifted = assertIs<State.Selecting>(vm.state.value).lifted!!
        assertEquals(12, lifted.left)
        assertEquals(1, lifted.top)
        assertEquals(6, lifted.pixels.width)
    }

    @Test
    fun `confirming cuts out the selection and switching effects uses the cache`() {
        val vm = started()
        vm.toggle(1)
        vm.confirmSelection()
        assertEquals(listOf(setOf(0)), engine.composed)
        val first = assertIs<State.Preview>(vm.state.value)
        assertEquals(StickerEffect.OUTLINE, first.effect)
        assertFalse(first.rendering)
        assertEquals(listOf(StickerEffect.OUTLINE), engine.applied)

        vm.selectEffect(StickerEffect.COMIC)
        assertEquals(StickerEffect.COMIC, assertIs<State.Preview>(vm.state.value).effect)
        vm.selectEffect(StickerEffect.OUTLINE)
        val again = assertIs<State.Preview>(vm.state.value)
        assertSame(first.sticker, again.sticker)
        assertEquals(listOf(StickerEffect.OUTLINE, StickerEffect.COMIC), engine.applied)

        vm.selectEffect(StickerEffect.ORIGINAL)
        assertEquals(2, engine.applied.size, "the original needs no effect pass")
    }

    @Test
    fun `back from the preview returns to the selection and a new cutout drops the cache`() {
        val vm = started()
        vm.confirmSelection()
        assertTrue(vm.back())
        assertEquals(setOf(0, 1), assertIs<State.Selecting>(vm.state.value).selected)
        assertFalse(vm.back(), "nothing before the selection: close")

        vm.toggle(0)
        vm.confirmSelection()
        assertEquals(listOf(setOf(0, 1), setOf(1)), engine.composed)
        assertEquals(listOf(StickerEffect.OUTLINE, StickerEffect.OUTLINE), engine.applied)
    }

    @Test
    fun `a failed effect is retryable`() {
        engine.failEffects = true
        val vm = started()
        vm.confirmSelection()
        assertEquals(State.Error(ErrorKind.RENDER_FAILED, retryable = true), vm.state.value)

        engine.failEffects = false
        vm.retry()
        assertIs<State.Preview>(vm.state.value)
    }

    @Test
    fun `saving into a full store reports it and does not refresh`() {
        val vm = started()
        vm.confirmSelection()
        store.outcome = StickerCreatorStore.SaveOutcome.FULL
        vm.save()
        assertEquals(State.Error(ErrorKind.STORE_FULL, retryable = false), vm.state.value)
        assertEquals(0, store.refreshes)
    }

    @Test
    fun `saving stores the previewed sticker and refreshes the palette`() {
        val vm = started()
        vm.confirmSelection()
        vm.selectEffect(StickerEffect.PUFFY)
        val shown = assertIs<State.Preview>(vm.state.value).sticker
        vm.save()
        assertEquals(State.Saved, vm.state.value)
        assertEquals(listOf(shown), store.saved)
        assertEquals(1, store.refreshes)
    }

    @Test
    fun `a failed write is retryable`() {
        store.failSaves = 1
        val vm = started()
        vm.confirmSelection()
        vm.save()
        assertEquals(State.Error(ErrorKind.SAVE_FAILED, retryable = true), vm.state.value)

        vm.retry()
        assertEquals(State.Saved, vm.state.value)
        assertEquals(1, store.saved.size)
        assertEquals(1, store.refreshes)
    }

    private companion object {
        fun subject(left: Int, top: Int, width: Int, height: Int) =
            DetectedSubject(left, top, width, height, FloatArray(width * height) { 1f })
    }
}
