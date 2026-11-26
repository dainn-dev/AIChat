package com.aichat.keyboard.core.network.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/*
 * Wire models for the AIChat backend. Classes that carry passwords or tokens override toString() so they can
 * never end up in a log or crash report by accident.
 * Field names mirror the NestJS DTOs exactly
 * (backend/src/auth/dto, conversations/dto, memories/dto, keyboard/dto) and the contracts in
 * backend/docs/keyboard-api.md and backend/docs/phase4-integration.md.
 *
 * Optional request fields default to null. The shared Json instance uses `explicitNulls = false`, so a null
 * field is left out of the request body instead of being sent as `null` (the backend rejects e.g.
 * `"lang": null` because `lang` must be `en | vi` when present).
 */

// ---------------------------------------------------------------------------------------------------------------
// Auth (/auth/*)
// ---------------------------------------------------------------------------------------------------------------

@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null,
) {
    override fun toString(): String = "SignupRequest(<redacted>)"
}

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
) {
    override fun toString(): String = "LoginRequest(<redacted>)"
}

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
) {
    override fun toString(): String = "RefreshRequest(<redacted>)"
}

@Serializable
data class LogoutRequest(
    @SerialName("refresh_token") val refreshToken: String,
) {
    override fun toString(): String = "LogoutRequest(<redacted>)"
}

/** `PublicUser` on the backend. */
@Serializable
data class PublicUser(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
    val tier: String,
    @SerialName("created_at") val createdAt: String,
)

/** Response of `POST /auth/signup` and `POST /auth/login`. */
@Serializable
data class AuthTokensResponse(
    val user: PublicUser,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
) {
    override fun toString(): String = "AuthTokensResponse(user=$user, tokens=<redacted>)"
}

/** Response of `POST /auth/refresh`. The refresh token is rotated: the old one is revoked server-side. */
@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
) {
    override fun toString(): String = "RefreshResponse(<redacted>)"
}

/**
 * Tier ids (`common/tiers.ts`). Kept as a plain string on the wire models so that a tier added server-side
 * does not make `/me` undecodable on older clients.
 */
object Tier {
    const val FREE = "free"
    const val PRO = "pro"
}

// ---------------------------------------------------------------------------------------------------------------
// Me (/me, /me/settings)
// ---------------------------------------------------------------------------------------------------------------

/** Daily usage snapshot. A null `*_limit` means unlimited (Pro). */
@Serializable
data class Usage(
    @SerialName("replies_used") val repliesUsed: Int,
    @SerialName("replies_limit") val repliesLimit: Int? = null,
    @SerialName("screenshots_used") val screenshotsUsed: Int,
    @SerialName("screenshots_limit") val screenshotsLimit: Int? = null,
)

@Serializable
data class UserSettings(
    /** Store keyboard-captured conversations so the Memory engine learns from them. Defaults to false. */
    @SerialName("memory_opt_in") val memoryOptIn: Boolean,
)

@Serializable
data class MeResponse(
    val user: PublicUser,
    val tier: String,
    val usage: Usage,
    val settings: UserSettings,
)

/** `PATCH /me/settings` body. `memory_opt_in` is required and must be a JSON boolean. */
@Serializable
data class UpdateSettingsRequest(
    @SerialName("memory_opt_in") val memoryOptIn: Boolean,
)

/**
 * Response of `PATCH /me/settings`. Turning `memory_opt_in` off deletes every keyboard-captured conversation and
 * the memories learned only from them (screenshot/share data is untouched); [deleted] then reports what was
 * removed. Optional, so responses from backends that do not send it still decode.
 */
@Serializable
data class SettingsResponse(
    val settings: UserSettings,
    val deleted: DeletedCounts? = null,
)

@Serializable
data class DeletedCounts(
    val conversations: Int = 0,
    val memories: Int = 0,
)

// ---------------------------------------------------------------------------------------------------------------
// Conversations (/conversations)
// ---------------------------------------------------------------------------------------------------------------

/** Metadata-only view of a stored conversation. It never carries message content. */
@Serializable
data class ConversationSummary(
    val id: String,
    val platform: String,
    @SerialName("contact_label") val contactLabel: String? = null,
    /** `keyboard | ocr | share | app`. */
    val source: String,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ConversationListResponse(
    val conversations: List<ConversationSummary>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

// ---------------------------------------------------------------------------------------------------------------
// Memories (/memories)
// ---------------------------------------------------------------------------------------------------------------

/** Dashboard view of a memory row (`MemoryView` on the backend). */
@Serializable
data class Memory(
    val id: String,
    /** `interest | job | birthday | fact`. */
    val kind: String,
    val content: String,
    @SerialName("contact_label") val contactLabel: String? = null,
    /** `active | pending_review | superseded | dismissed`. */
    val status: String,
    val confidence: Double? = null,
    /** `manual | extracted`. */
    val source: String,
    @SerialName("source_ref") val sourceRef: String? = null,
    @SerialName("created_at") val createdAt: String,
)

// ---------------------------------------------------------------------------------------------------------------
// Keyboard (/v1/keyboard/*)
// ---------------------------------------------------------------------------------------------------------------

@Serializable
enum class Tone {
    @SerialName("Friendly") FRIENDLY,
    @SerialName("Funny") FUNNY,
    @SerialName("Flirty") FLIRTY,
    @SerialName("Professional") PROFESSIONAL,
    @SerialName("Mature") MATURE,
}

/** Output language from the keyboard's EN/VI toggle. */
@Serializable
enum class OutputLang {
    @SerialName("en") EN,
    @SerialName("vi") VI,
}

@Serializable
enum class MessageSender {
    @SerialName("me") ME,
    @SerialName("them") THEM,
}

@Serializable
data class ChatMessage(
    val sender: MessageSender,
    val content: String,
)

/**
 * `POST /v1/keyboard/reply`.
 *
 * The capture is stored server-side only when the user opted in (`memory_opt_in`), both [platform] and
 * [contactLabel] are present, and [remember] is not `false`. Send `remember = false` for incognito/private
 * chats and for contacts the user erased ("Forget this chat"): the backend keeps no tombstone.
 */
@Serializable
data class KeyboardReplyRequest(
    val conversation: List<ChatMessage>,
    val tone: Tone,
    /** 1-5, backend default 3. */
    val count: Int? = null,
    /** Chat app id, `[a-z0-9._-]{1,64}` after the backend lower-cases it, e.g. `zalo`. */
    val platform: String? = null,
    /** Chat partner's display name, 1-128 chars. */
    @SerialName("contact_label") val contactLabel: String? = null,
    /** Omitted: the model replies in the conversation's own language. */
    val lang: OutputLang? = null,
    /** Only an explicit `false` disables storage. */
    val remember: Boolean? = null,
)

@Serializable
data class ReplySuggestion(
    val tone: Tone,
    val text: String,
)

@Serializable
data class KeyboardReplyResponse(
    val suggestions: List<ReplySuggestion>,
    /** Stored conversation id, or null. Null does NOT mean nothing was stored; see keyboard-api.md. */
    @SerialName("conversation_id") val conversationId: String? = null,
    val usage: Usage,
    /**
     * 0-3 sticker tag wire names that fit the conversation (`StickerTag` vocabulary in the app). Optional: an
     * older backend leaves it out, and a `null` or malformed value decodes as empty rather than failing the reply.
     * Unknown names are kept here; the app drops them.
     */
    @SerialName("sticker_tags")
    @Serializable(with = LenientStringListSerializer::class)
    val stickerTags: List<String> = emptyList(),
)

/**
 * Decodes a JSON array of strings, skipping non-string elements; anything else (`null`, an object) becomes an empty
 * list. For optional, purely decorative fields that must never make a whole response fail to parse.
 */
internal object LenientStringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }
    }

    override fun serialize(encoder: Encoder, value: List<String>) = delegate.serialize(encoder, value)
}

/** `POST /v1/keyboard/rewrite`. */
@Serializable
data class KeyboardRewriteRequest(
    val text: String,
    val tone: Tone,
    val lang: OutputLang? = null,
)

/** `POST /v1/keyboard/translate`. */
@Serializable
data class KeyboardTranslateRequest(
    val text: String,
    /** Code or name, 2-32 chars, e.g. `en`. */
    @SerialName("target_lang") val targetLang: String,
    /** Omitted: auto-detect. */
    @SerialName("source_lang") val sourceLang: String? = null,
)

/** Response of rewrite and translate. */
@Serializable
data class KeyboardTextResponse(
    val text: String,
    val usage: Usage,
)
