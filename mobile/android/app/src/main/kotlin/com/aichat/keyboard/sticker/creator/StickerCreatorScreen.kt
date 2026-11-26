/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aichat.keyboard.sticker.UserStickerFiles
import com.aichat.keyboard.sticker.creator.StickerCreatorViewModel.ErrorKind
import com.aichat.keyboard.sticker.creator.StickerCreatorViewModel.State
import dev.patrickgold.florisboard.R
import kotlin.math.roundToInt
import org.florisboard.lib.compose.stringRes

/** What the screen can ask its host activity to do. */
interface StickerCreatorActions {
    fun pickPhoto()
    fun pickAnother()
    fun tap(x: Int, y: Int)
    fun toggle(index: Int)
    fun selectAll()
    fun confirmSelection()
    fun useWholePhoto()
    fun selectEffect(effect: StickerEffect)
    fun save()
    fun retry()
    fun back()
    fun close()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerCreatorScreen(state: State, actions: StickerCreatorActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringRes(R.string.aichat__sticker_creator__title)) },
                navigationIcon = {
                    IconButton(onClick = actions::back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringRes(R.string.aichat__sticker_creator__back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                State.CheckingModel -> Progress(stringRes(R.string.aichat__sticker_creator__checking))
                State.Decoding -> Progress(stringRes(R.string.aichat__sticker_creator__decoding))
                State.Segmenting -> Progress(stringRes(R.string.aichat__sticker_creator__segmenting))
                State.Saving, State.Saved -> Progress(stringRes(R.string.aichat__sticker_creator__saving))
                State.Downloading -> DownloadContent()
                State.Unsupported -> MessageContent(
                    message = stringRes(R.string.aichat__sticker_creator__unsupported),
                    primary = stringRes(R.string.aichat__sticker_creator__close) to actions::close,
                )
                State.Ready -> MessageContent(
                    message = stringRes(R.string.aichat__sticker_creator__pick_prompt),
                    primary = stringRes(R.string.aichat__sticker_creator__pick_photo) to actions::pickPhoto,
                )
                is State.Selecting -> SelectingContent(state, actions)
                is State.Preview -> PreviewContent(state, actions)
                is State.Error -> ErrorContent(state, actions)
            }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DownloadContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringRes(R.string.aichat__sticker_creator__download_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringRes(R.string.aichat__sticker_creator__download_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp))
    }
}

@Composable
private fun MessageContent(
    message: String,
    primary: Pair<String, () -> Unit>?,
    secondary: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        primary?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
        secondary.forEach { (label, onClick) ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClick) { Text(label) }
        }
    }
}

@Composable
private fun ErrorContent(state: State.Error, actions: StickerCreatorActions) {
    val close = stringRes(R.string.aichat__sticker_creator__close) to actions::close
    val retry = stringRes(R.string.aichat__sticker_creator__retry) to actions::retry
    val pickAnother = stringRes(R.string.aichat__sticker_creator__pick_another) to actions::pickAnother
    when (state.kind) {
        ErrorKind.NO_SUBJECTS -> MessageContent(
            message = stringRes(R.string.aichat__sticker_creator__error_no_subjects),
            primary = stringRes(R.string.aichat__sticker_creator__use_whole_photo) to actions::useWholePhoto,
            secondary = listOf(pickAnother, close),
        )
        ErrorKind.UNREADABLE_IMAGE -> MessageContent(
            message = stringRes(R.string.aichat__sticker_creator__error_unreadable),
            primary = pickAnother,
            secondary = listOf(close),
        )
        ErrorKind.STORE_FULL -> MessageContent(
            message = stringRes(R.string.aichat__stickers__import_full, "max" to UserStickerFiles.MAX_USER_STICKERS),
            primary = close,
        )
        else -> MessageContent(
            message = stringRes(
                when (state.kind) {
                    ErrorKind.DOWNLOAD_FAILED -> R.string.aichat__sticker_creator__error_download
                    ErrorKind.SEGMENT_FAILED -> R.string.aichat__sticker_creator__error_segment
                    ErrorKind.SAVE_FAILED -> R.string.aichat__sticker_creator__error_save
                    else -> R.string.aichat__sticker_creator__error_render
                },
            ),
            primary = if (state.retryable) retry else close,
            secondary = if (state.retryable) listOf(close) else emptyList(),
        )
    }
}

@Composable
private fun SelectingContent(state: State.Selecting, actions: StickerCreatorActions) {
    val photo = rememberImageBitmap(state.image)
    val overlay = rememberImageBitmap(state.overlay)
    val lifted = state.lifted?.let { layer -> layer to rememberImageBitmap(layer.pixels) }
    val lift = remember { Animatable(1f) }
    LaunchedEffect(state.selected) {
        // iOS-like "lift": a quick pop, then the selection settles slightly raised.
        lift.snapTo(1f)
        lift.animateTo(LIFT_PEAK, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        lift.animateTo(LIFT_REST, tween(durationMillis = 160))
    }
    val description = stringRes(
        R.string.aichat__sticker_creator__photo_description,
        "count" to state.subjectCount,
        "selected" to state.selected.size,
    )
    val toggleLabels = (0 until state.subjectCount).map { index ->
        stringRes(R.string.aichat__sticker_creator__subject_toggle, "index" to index + 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringRes(R.string.aichat__sticker_creator__select_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics {
                    contentDescription = description
                    customActions = toggleLabels.mapIndexed { index, label ->
                        CustomAccessibilityAction(label) {
                            actions.toggle(index)
                            true
                        }
                    }
                }
                .pointerInput(state.image) {
                    detectTapGestures { offset ->
                        StickerCreatorMath.viewToImage(
                            size.width.toFloat(),
                            size.height.toFloat(),
                            state.image.width,
                            state.image.height,
                            offset.x,
                            offset.y,
                        )?.let { (x, y) -> actions.tap(x, y) }
                    }
                },
        ) {
            val rect = StickerCreatorMath.fit(size.width, size.height, state.image.width, state.image.height)
            drawFitted(photo, rect.left, rect.top, rect.width, rect.height)
            drawFitted(overlay, rect.left, rect.top, rect.width, rect.height)
            lifted?.let { (layer, bitmap) ->
                val scale = rect.scale(state.image.width)
                val left = rect.left + layer.left * scale
                val top = rect.top + layer.top * scale
                val width = layer.pixels.width * scale
                val height = layer.pixels.height * scale
                val raise = lift.value
                val pivot = Offset(left + width / 2f, top + height / 2f)
                withTransform({ scale(raise, raise, pivot) }) {
                    // A soft shadow in the subject's own shape, falling a little lower the higher it is lifted.
                    val drop = (raise - 1f) * SHADOW_DROP_PER_LIFT * density
                    drawFitted(bitmap, left, top + drop, width, height, SHADOW_FILTER)
                    drawFitted(bitmap, left, top, width, height)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = actions::selectAll, enabled = state.selected.size < state.subjectCount) {
                Text(stringRes(R.string.aichat__sticker_creator__select_all))
            }
            Button(onClick = actions::confirmSelection, enabled = state.selected.isNotEmpty()) {
                Text(stringRes(R.string.aichat__sticker_creator__next))
            }
        }
    }
}

@Composable
private fun PreviewContent(state: State.Preview, actions: StickerCreatorActions) {
    val sticker = state.sticker?.let { rememberImageBitmap(it) }
    val effectLabel = effectLabel(state.effect)
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val previewDescription = stringRes(R.string.aichat__sticker_creator__preview_description, "effect" to effectLabel)
            Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = previewDescription }) {
                drawCheckerboard()
                if (sticker != null) {
                    val rect = StickerCreatorMath.fit(size.width, size.height, sticker.width, sticker.height)
                    drawFitted(sticker, rect.left, rect.top, rect.width, rect.height)
                }
            }
            if (state.rendering) CircularProgressIndicator()
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringRes(R.string.aichat__sticker_creator__effect_title),
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StickerCreatorViewModel.EFFECTS.forEach { effect ->
                FilterChip(
                    selected = effect == state.effect,
                    onClick = { actions.selectEffect(effect) },
                    label = { Text(effectLabel(effect)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = actions::save,
            enabled = state.sticker != null && !state.rendering,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringRes(R.string.aichat__sticker_creator__save))
        }
    }
}

@Composable
private fun effectLabel(effect: StickerEffect): String = stringRes(
    when (effect) {
        StickerEffect.ORIGINAL -> R.string.aichat__sticker_creator__effect_original
        StickerEffect.OUTLINE -> R.string.aichat__sticker_creator__effect_outline
        StickerEffect.COMIC -> R.string.aichat__sticker_creator__effect_comic
        StickerEffect.PUFFY -> R.string.aichat__sticker_creator__effect_puffy
        StickerEffect.SHINY -> R.string.aichat__sticker_creator__effect_shiny
    },
)

/** The bitmap of [pixels], converted once per image (the state keeps the same instance until it changes). */
@Composable
private fun rememberImageBitmap(pixels: StickerPixels): ImageBitmap =
    remember(pixels) { StickerBitmaps.toBitmap(pixels).asImageBitmap() }

private fun DrawScope.drawFitted(
    image: ImageBitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    colorFilter: ColorFilter? = null,
) {
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
        colorFilter = colorFilter,
    )
}

/** The usual "transparent" pattern, so the sticker's cut edge and outline are visible. */
private fun DrawScope.drawCheckerboard() {
    val cell = CHECKER_CELL_DP * density
    drawRect(CHECKER_LIGHT)
    var row = 0
    var y = 0f
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(CHECKER_DARK, topLeft = Offset(x, y), size = Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row++
    }
}

private const val LIFT_PEAK = 1.06f
private const val LIFT_REST = 1.03f
private const val SHADOW_DROP_PER_LIFT = 200f
private const val CHECKER_CELL_DP = 12f
private val CHECKER_LIGHT = Color(0xFFF2F2F2)
private val CHECKER_DARK = Color(0xFFD9D9D9)
private val SHADOW_FILTER = ColorFilter.tint(Color.Black.copy(alpha = 0.35f), BlendMode.SrcIn)
