package com.aichat.keyboard.sticker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.florisboard.lib.compose.stringRes

/**
 * "Tạo sticker chữ": a small dialog-like screen, opened from the "Của tôi" add menu, where the user types a phrase,
 * picks a bubble color and saves it as one of their stickers. The keyboard cannot host a text field of its own
 * (it is the input method), hence an activity. Not exported.
 *
 * The sticker is drawn by [StickerRenderer] exactly like the built-in text pack and stored as a PNG in
 * [UserStickerFiles], so it behaves like any gallery sticker afterwards (send, delete).
 */
class TextStickerMakerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ProvideLocalizedResources(this, appName = R.string.aichat_app_name) {
                FlorisAppTheme(theme = AppTheme.AUTO) {
                    TextStickerMakerScreen(onSave = ::save, onClose = ::finish)
                }
            }
        }
    }

    private suspend fun save(source: StickerSource.Text) {
        val appContext = applicationContext
        val repository = StickerRepository.get(appContext)
        val outcome = withContext(Dispatchers.IO) { write(repository.userFiles, source) }
        when (outcome) {
            Outcome.ADDED -> {
                repository.refresh()
                appContext.showShortToast(R.string.aichat__stickers__text_saved)
                finish()
            }
            Outcome.FULL -> appContext.showShortToast(
                R.string.aichat__stickers__import_full,
                "max" to UserStickerFiles.MAX_USER_STICKERS,
            )
            Outcome.FAILED -> appContext.showShortToast(R.string.aichat__stickers__text_failed)
        }
    }

    private enum class Outcome { ADDED, FULL, FAILED }

    private fun write(files: UserStickerFiles, source: StickerSource.Text): Outcome {
        val target = files.newStickerFile() ?: return if (files.isFull()) Outcome.FULL else Outcome.FAILED
        // Written next to the target under a name the store ignores, then renamed: the palette never lists a
        // half-written sticker.
        val partial = File(target.parentFile, "${target.name}.partial")
        val bitmap = try {
            StickerRenderer.render(source)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot render text sticker", e)
            null
        } ?: return Outcome.FAILED
        return try {
            val written = partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            if (written && partial.renameTo(target)) Outcome.ADDED else Outcome.FAILED
        } catch (e: IOException) {
            Log.w(TAG, "Cannot save text sticker", e)
            Outcome.FAILED
        } finally {
            bitmap.recycle()
            partial.delete()
        }
    }

    companion object {
        private const val TAG = "TextStickerMaker"
        private const val PNG_QUALITY = 100

        /** Opens the maker from the keyboard (a service context, hence the new task). */
        fun launch(context: Context) {
            val intent = Intent(context, TextStickerMakerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

private val PreviewSize = 168.dp
private val SwatchSize = 36.dp
private const val PREVIEW_PLACEHOLDER = "Aa"

@Composable
private fun TextStickerMakerScreen(onSave: suspend (StickerSource.Text) -> Unit, onClose: () -> Unit) {
    val colors = BuiltInStickers.textColors
    var text by rememberSaveable { mutableStateOf("") }
    var colorIndex by rememberSaveable { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val color = colors[colorIndex.coerceIn(colors.indices)]
    val stickerText = TextStickerDraft.layout(text)

    val preview by produceState<ImageBitmap?>(null, stickerText, color) {
        val source = StickerSource.Text(stickerText.ifEmpty { PREVIEW_PLACEHOLDER }, color.background, color.foreground)
        value = withContext(Dispatchers.Default) { StickerRenderer.render(source)?.asImageBitmap() }
    }

    // Scrim: a tap outside the card closes the screen, like a dialog.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                // Taps on the card must not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = stringRes(R.string.aichat__stickers__add_text), style = MaterialTheme.typography.titleLarge)

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val description = stringRes(R.string.aichat__stickers__text_preview)
                    Box(
                        modifier = Modifier.size(PreviewSize).semantics { contentDescription = description },
                    ) {
                        preview?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                    }
                }

                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (TextStickerDraft.accepts(it)) text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    placeholder = { Text(stringRes(R.string.aichat__stickers__text_hint)) },
                    supportingText = { Text("${text.length}/${TextStickerDraft.MAX_CHARS}") },
                    minLines = 1,
                    maxLines = TextStickerDraft.MAX_LINES,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    colors.forEachIndexed { index, swatch ->
                        val selected = index == colorIndex
                        val description = stringRes(R.string.aichat__stickers__text_color, "number" to index + 1)
                        Box(
                            modifier = Modifier
                                .size(SwatchSize)
                                .clip(CircleShape)
                                .background(Color(swatch.background))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(0.12f),
                                    shape = CircleShape,
                                )
                                .clickable { colorIndex = index }
                                .semantics {
                                    contentDescription = description
                                    this.selected = selected
                                },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text(stringRes(R.string.aichat__stickers__cancel)) }
                    Button(
                        enabled = !saving && TextStickerDraft.isSavable(text),
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    onSave(StickerSource.Text(stickerText, color.background, color.foreground))
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) {
                        Text(stringRes(R.string.aichat__stickers__text_save))
                    }
                }
            }
        }
    }
}
