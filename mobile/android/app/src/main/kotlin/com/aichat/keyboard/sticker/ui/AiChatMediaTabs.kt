package com.aichat.keyboard.sticker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon

/** The panels of the media input (the emoji/sticker screen). */
enum class AiChatMediaTab { EMOJI, STICKERS }

/** Emoji and sticker switches for the media input's bottom row, styled like its other buttons. */
@Composable
fun AiChatMediaTabButtons(selected: AiChatMediaTab, onSelect: (AiChatMediaTab) -> Unit) {
    MediaTabButton(
        icon = Icons.Outlined.EmojiEmotions,
        label = stringRes(R.string.aichat__media__tab_emoji),
        isSelected = selected == AiChatMediaTab.EMOJI,
        onClick = { onSelect(AiChatMediaTab.EMOJI) },
    )
    @Suppress("DEPRECATION") // The AutoMirrored variant is not needed: the media input is always laid out LTR.
    MediaTabButton(
        icon = Icons.Outlined.StickyNote2,
        label = stringRes(R.string.aichat__media__tab_stickers),
        isSelected = selected == AiChatMediaTab.STICKERS,
        onClick = { onSelect(AiChatMediaTab.STICKERS) },
    )
}

@Composable
private fun MediaTabButton(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val inputFeedbackController = LocalInputFeedbackController.current
    val selector = if (isSelected) SnyggSelector.FOCUS else SnyggSelector.NONE
    SnyggBox(
        elementName = FlorisImeUi.MediaBottomRowButton.elementName,
        selector = selector,
        modifier = Modifier.fillMaxHeight(),
        clickAndSemanticsModifier = Modifier
            .semantics {
                contentDescription = label
                this.selected = isSelected
            }
            .clickable(role = Role.Tab) {
                inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        SnyggIcon(
            elementName = FlorisImeUi.MediaBottomRowButton.elementName,
            selector = selector,
            imageVector = icon,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
