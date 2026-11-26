package com.aichat.keyboard.sticker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.keyboard.sticker.BuiltInStickers
import com.aichat.keyboard.sticker.Sticker
import com.aichat.keyboard.sticker.StickerImportActivity
import com.aichat.keyboard.sticker.StickerPack
import com.aichat.keyboard.sticker.StickerRepository
import com.aichat.keyboard.sticker.StickerSendFlow
import com.aichat.keyboard.sticker.StickerSource
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

private val TileMinSize = 80.dp
private val TabHeight = 40.dp
private val TextStickerShape = RoundedCornerShape(18.dp)
private const val EMOJI_STICKER_FONT_SIZE = 40
private const val DELETE_BADGE_COLOR = 0xFFFF3B30

/**
 * The sticker panel of the media input: pack tabs on top, a grid of stickers below. Tap sends a sticker; in the
 * "Của tôi" pack the first tile adds images from the gallery and a long press shows delete badges.
 */
@Composable
fun StickerPaletteView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inputFeedbackController = LocalInputFeedbackController.current
    val repository = remember { StickerRepository.get(context) }
    val userStickers by repository.userStickers.collectAsState()
    LaunchedEffect(Unit) { repository.refresh() }

    val userPackTitle = stringRes(R.string.aichat__stickers__my_pack)
    val packs = remember(userStickers, userPackTitle) { repository.packs(userStickers, userPackTitle) }
    var selectedPackId by rememberSaveable { mutableStateOf(BuiltInStickers.packs.first().id) }
    val selectedPack = packs.firstOrNull { it.id == selectedPackId } ?: packs.first()
    var editing by remember(selectedPack.id) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    fun send(sticker: Sticker) {
        if (sending) return
        sending = true
        scope.launch {
            try {
                StickerSendFlow.sendAndReport(context, sticker)
            } finally {
                sending = false
            }
        }
    }

    Column(modifier = modifier) {
        StickerPackTabs(
            packs = packs,
            selectedPackId = selectedPack.id,
            onSelect = {
                inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                selectedPackId = it
            },
        )
        LazyVerticalGrid(
            modifier = Modifier.weight(1f),
            columns = GridCells.Adaptive(TileMinSize),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selectedPack.isUserPack) {
                item(key = "add") {
                    AddStickerTile(onClick = {
                        inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                        StickerImportActivity.launch(context)
                    })
                }
                if (selectedPack.stickers.isEmpty()) {
                    item(key = "hint", span = { GridItemSpan(maxLineSpan) }) { UserPackHint() }
                }
            }
            items(selectedPack.stickers, key = { it.id }) { sticker ->
                StickerTile(
                    sticker = sticker,
                    showDelete = editing && selectedPack.isUserPack,
                    onTap = {
                        inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                        if (editing) editing = false else send(sticker)
                    },
                    onLongPress = {
                        if (selectedPack.isUserPack) {
                            inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
                            editing = true
                        }
                    },
                    onDelete = { scope.launch { repository.delete(sticker) } },
                )
            }
        }
    }
}

@Composable
private fun StickerPackTabs(packs: List<StickerPack>, selectedPackId: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TabHeight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (pack in packs) {
            val selected = pack.id == selectedPackId
            val style = rememberSnyggThemeQuery(
                elementName = FlorisImeUi.MediaEmojiTab.elementName,
                selector = if (selected) SnyggSelector.FOCUS else SnyggSelector.NONE,
            )
            val foreground = style.foreground()
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) foreground.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onSelect(pack.id) }
                    .semantics { contentDescription = pack.title }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = pack.icon, color = foreground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StickerTile(
    sticker: Sticker,
    showDelete: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
) {
    SnyggBox(
        elementName = FlorisImeUi.MediaEmojiKey.elementName,
        modifier = Modifier
            .aspectRatio(1f)
            .semantics { contentDescription = sticker.description }
            .pointerInput(sticker.id) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            },
    ) {
        StickerThumbnail(sticker = sticker, modifier = Modifier.fillMaxSize().padding(4.dp))
        if (showDelete) {
            val description = stringRes(R.string.aichat__stickers__delete)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(DELETE_BADGE_COLOR))
                    .clickable(onClick = onDelete)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun StickerThumbnail(sticker: Sticker, modifier: Modifier = Modifier) {
    when (val source = sticker.source) {
        is StickerSource.Emoji -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(text = source.emoji, fontSize = EMOJI_STICKER_FONT_SIZE.sp)
        }
        // Text stickers use the rendered image, so the preview has the fitted text of the sent sticker.
        is StickerSource.Text, is StickerSource.Asset, is StickerSource.UserFile -> {
            val context = LocalContext.current
            var bitmap by remember(sticker.id) { mutableStateOf(StickerThumbnails.cached(sticker)) }
            LaunchedEffect(sticker.id) {
                if (bitmap == null) bitmap = StickerThumbnails.load(context, sticker)
            }
            bitmap?.let { Image(bitmap = it, contentDescription = null, modifier = modifier) }
        }
    }
}

@Composable
private fun AddStickerTile(onClick: () -> Unit) {
    val foreground = rememberSnyggThemeQuery(FlorisImeUi.MediaEmojiKey.elementName).foreground()
    val description = stringRes(R.string.aichat__stickers__add)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(8.dp)
            .clip(TextStickerShape)
            .border(2.dp, foreground.copy(alpha = 0.35f), TextStickerShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, tint = foreground)
    }
}

@Composable
private fun UserPackHint() {
    val foreground = rememberSnyggThemeQuery(FlorisImeUi.MediaEmojiSubheader.elementName).foreground()
    Text(
        text = stringRes(R.string.aichat__stickers__empty_user),
        color = foreground,
        fontSize = 13.sp,
        modifier = Modifier.padding(8.dp),
    )
}
