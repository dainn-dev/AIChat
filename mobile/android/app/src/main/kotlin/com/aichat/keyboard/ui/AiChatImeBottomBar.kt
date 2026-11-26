package com.aichat.keyboard.ui

import com.aichat.keyboard.ai.AiAssist
import com.aichat.keyboard.ai.AiButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.media.KeyboardLikeButton
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggIcon

private val ButtonWidth = 56.dp
private val IconSize = 24.dp

/**
 * iOS-like strip under the keys: the language (globe) key and the AI button on the left, voice input on the right, so the key rows
 * themselves only keep 123 / emoji / space / return. Shown in every input mode so the keyboard height never jumps.
 */
@Composable
fun AiChatImeBottomBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val subtypeManager by context.subtypeManager()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()
    val barHeight = AiChatSizing.bottomBarHeightDp(LocalConfiguration.current.screenWidthDp.toFloat()).dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (subtypes.size > 1) {
            BarButton(
                keyData = TextKeyData.LANGUAGE_SWITCH,
                icon = Icons.Outlined.Language,
                description = stringRes(R.string.aichat__bottom_bar__switch_language),
            )
        }
        val aiController = remember { AiAssist.controller(context) }
        val keyboardManager by context.keyboardManager()
        AiButton(
            controller = aiController,
            modifier = Modifier.fillMaxHeight().width(ButtonWidth),
            iconModifier = Modifier.size(IconSize),
            // The chips live in the smartbar, which only the text layout has.
            onOpen = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
        )
        Spacer(modifier = Modifier.weight(1f))
        BarButton(
            keyData = TextKeyData.VOICE_INPUT,
            icon = Icons.Outlined.Mic,
            description = stringRes(R.string.aichat__bottom_bar__voice_input),
        )
    }
}

@Composable
private fun BarButton(keyData: KeyData, icon: ImageVector, description: String) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    KeyboardLikeButton(
        elementName = FlorisImeUi.SmartbarActionKey.elementName,
        inputEventDispatcher = keyboardManager.inputEventDispatcher,
        keyData = keyData,
        modifier = Modifier
            .fillMaxHeight()
            .width(ButtonWidth)
            .semantics { contentDescription = description },
    ) {
        SnyggIcon(
            elementName = FlorisImeUi.SmartbarActionKey.elementName,
            imageVector = icon,
            modifier = Modifier.size(IconSize),
        )
    }
}
