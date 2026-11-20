package com.aichat.keyboard.app.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.aichat.keyboard.core.network.AiChat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.jetpref.datastore.ui.Preference
import kotlinx.serialization.Serializable
import org.florisboard.lib.compose.stringRes

/**
 * AIChat account routes inside the settings app (`FlorisAppActivity`).
 *
 * Unlike most upstream routes these have NO `ui://florisboard/...` deep link: the account, conversation and
 * memory screens must not be openable by a web page or another app through the exported, BROWSABLE activity.
 */
object AiChatRoutes {
    /** Sign in / sign up when signed out, account details when signed in. */
    @Serializable
    object Account

    @Serializable
    object Conversations

    @Serializable
    object Memories
}

/** Registers the AIChat screens. Called from `Routes.AppNavHost`. */
fun NavGraphBuilder.aichatAccountGraph() {
    composable<AiChatRoutes.Account> { AiChatAccountScreen() }
    composable<AiChatRoutes.Conversations> { AiChatConversationsScreen() }
    composable<AiChatRoutes.Memories> { AiChatMemoriesScreen() }
}

/** "Tài khoản AIChat" entry at the top of the settings home screen. */
@Composable
fun AiChatAccountPreference() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val client = remember { AiChat.client(context) }
    val signedIn by client.isSignedIn.collectAsState(initial = false)

    Preference(
        icon = Icons.Outlined.AccountCircle,
        title = stringRes(R.string.aichat__account__title),
        summary = stringRes(
            if (signedIn) R.string.aichat__account__summary_signed_in else R.string.aichat__account__summary_signed_out,
        ),
        onClick = { navController.navigate(AiChatRoutes.Account) },
    )
}
