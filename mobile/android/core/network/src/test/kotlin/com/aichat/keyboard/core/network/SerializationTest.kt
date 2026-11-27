package com.aichat.keyboard.core.network

import com.aichat.keyboard.core.network.model.AuthTokensResponse
import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.ConversationListResponse
import com.aichat.keyboard.core.network.model.KeyboardCorrectRequest
import com.aichat.keyboard.core.network.model.KeyboardReplyRequest
import com.aichat.keyboard.core.network.model.KeyboardReplyResponse
import com.aichat.keyboard.core.network.model.KeyboardRewriteRequest
import com.aichat.keyboard.core.network.model.KeyboardTextResponse
import com.aichat.keyboard.core.network.model.KeyboardTranslateRequest
import com.aichat.keyboard.core.network.model.KeyboardWriteRequest
import com.aichat.keyboard.core.network.model.KeyboardSummarizeRequest
import com.aichat.keyboard.core.network.model.KeyboardSummaryResponse
import com.aichat.keyboard.core.network.model.LoginRequest
import com.aichat.keyboard.core.network.model.LogoutRequest
import com.aichat.keyboard.core.network.model.RefreshRequest
import com.aichat.keyboard.core.network.model.RefreshResponse
import com.aichat.keyboard.core.network.model.MeResponse
import com.aichat.keyboard.core.network.model.Memory
import com.aichat.keyboard.core.network.model.MessageSender
import com.aichat.keyboard.core.network.model.OutputLang
import com.aichat.keyboard.core.network.model.SettingsResponse
import com.aichat.keyboard.core.network.model.SignupRequest
import com.aichat.keyboard.core.network.model.Tier
import com.aichat.keyboard.core.network.model.Tone
import com.aichat.keyboard.core.network.model.UpdateSettingsRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SerializationTest {
    private val json = AiChatClientFactory.json

    private fun encode(block: () -> String): JsonObject = json.parseToJsonElement(block()).jsonObject

    @Test
    fun `reply request matches the keyboard-api doc example`() {
        val request = KeyboardReplyRequest(
            platform = "zalo",
            contactLabel = "Linh",
            lang = OutputLang.VI,
            remember = true,
            conversation = listOf(
                ChatMessage(MessageSender.THEM, "tối nay đi ăn không?"),
                ChatMessage(MessageSender.ME, "ok luôn"),
            ),
            tone = Tone.FLIRTY,
            count = 3,
        )
        val expected = json.parseToJsonElement(
            """{
              "platform": "zalo",
              "contact_label": "Linh",
              "lang": "vi",
              "remember": true,
              "conversation": [
                { "sender": "them", "content": "tối nay đi ăn không?" },
                { "sender": "me", "content": "ok luôn" }
              ],
              "tone": "Flirty",
              "count": 3
            }""",
        )
        assertEquals(expected, encode { json.encodeToString(KeyboardReplyRequest.serializer(), request) })
    }

    @Test
    fun `optional reply fields are omitted, not sent as null`() {
        val body = encode {
            json.encodeToString(
                KeyboardReplyRequest.serializer(),
                KeyboardReplyRequest(listOf(ChatMessage(MessageSender.THEM, "hi")), Tone.FRIENDLY),
            )
        }
        assertEquals(setOf("conversation", "tone"), body.keys)
    }

    @Test
    fun `remember false is sent explicitly`() {
        val body = encode {
            json.encodeToString(
                KeyboardReplyRequest.serializer(),
                KeyboardReplyRequest(listOf(ChatMessage(MessageSender.THEM, "hi")), Tone.FRIENDLY, remember = false),
            )
        }
        assertEquals("false", body["remember"].toString())
    }

    @Test
    fun `rewrite and translate requests match the doc examples`() {
        assertEquals(
            json.parseToJsonElement("""{ "text": "ok sounds good", "tone": "Professional", "lang": "en" }"""),
            encode {
                json.encodeToString(
                    KeyboardRewriteRequest.serializer(),
                    KeyboardRewriteRequest("ok sounds good", Tone.PROFESSIONAL, OutputLang.EN),
                )
            },
        )
        assertEquals(
            json.parseToJsonElement("""{ "text": "hẹn gặp lại nhé", "target_lang": "en", "source_lang": "vi" }"""),
            encode {
                json.encodeToString(
                    KeyboardTranslateRequest.serializer(),
                    KeyboardTranslateRequest("hẹn gặp lại nhé", targetLang = "en", sourceLang = "vi"),
                )
            },
        )
    }

    @Test
    fun `signup and settings requests use snake case`() {
        assertEquals(
            json.parseToJsonElement("""{"email":"a@b.co","password":"secret123","display_name":"Linh"}"""),
            encode { json.encodeToString(SignupRequest.serializer(), SignupRequest("a@b.co", "secret123", "Linh")) },
        )
        assertEquals(
            json.parseToJsonElement("""{"memory_opt_in":true}"""),
            encode { json.encodeToString(UpdateSettingsRequest.serializer(), UpdateSettingsRequest(true)) },
        )
        assertEquals(
            json.parseToJsonElement("""{"training_opt_in":false}"""),
            encode {
                json.encodeToString(UpdateSettingsRequest.serializer(), UpdateSettingsRequest(trainingOptIn = false))
            },
        )
    }

    @Test
    fun `write request matches the doc example`() {
        assertEquals(
            json.parseToJsonElement("""{ "instruction": "chúc sinh nhật sếp", "lang": "vi" }"""),
            encode {
                json.encodeToString(
                    KeyboardWriteRequest.serializer(),
                    KeyboardWriteRequest("chúc sinh nhật sếp", OutputLang.VI),
                )
            },
        )
    }

    @Test
    fun `correct request is just the text`() {
        assertEquals(
            json.parseToJsonElement("""{ "text": "toi nay ban ranh ko" }"""),
            encode {
                json.encodeToString(KeyboardCorrectRequest.serializer(), KeyboardCorrectRequest("toi nay ban ranh ko"))
            },
        )
    }

    @Test
    fun `summarize request matches the doc example and omits unset fields`() {
        val conversation = listOf(ChatMessage(MessageSender.THEM, "cuối tuần đi cafe ko?"))
        assertEquals(
            json.parseToJsonElement(
                """{ "conversation": [{ "sender": "them", "content": "cuối tuần đi cafe ko?" }],
                    "lang": "vi", "contact_label": "Linh" }""",
            ),
            encode {
                json.encodeToString(
                    KeyboardSummarizeRequest.serializer(),
                    KeyboardSummarizeRequest(conversation, OutputLang.VI, contactLabel = "Linh"),
                )
            },
        )
        val bare = encode {
            json.encodeToString(KeyboardSummarizeRequest.serializer(), KeyboardSummarizeRequest(conversation))
        }.jsonObject
        assertEquals(setOf("conversation"), bare.keys)
    }

    @Test
    fun `decodes the summarize response from the doc`() {
        val body = """{"summary":"Linh rủ bạn đi cà phê.","interest_score":78,
            "red_flags":[{"code":"pressure","message":"Hơi hối thúc"}],"degraded":false,"usage":${Payloads.USAGE}}"""
        val response = json.decodeFromString(KeyboardSummaryResponse.serializer(), body)
        assertEquals(78, response.interestScore)
        assertEquals("Hơi hối thúc", response.redFlags.single().message)
        assertEquals(false, response.degraded)
    }

    @Test
    fun `decodes the reply response from the doc`() {
        val response = json.decodeFromString(KeyboardReplyResponse.serializer(), Payloads.REPLY)
        assertEquals(Tone.FLIRTY, response.suggestions.single().tone)
        assertEquals("7b0c4a3e-2f4e-4b8a-9a52-0d6f1f4a9c11", response.conversationId)
        assertEquals(20, response.usage.repliesLimit)
    }

    @Test
    fun `decodes a reply response with conversation_id null`() {
        val body = """{"suggestions":[],"conversation_id":null,"usage":${Payloads.USAGE}}"""
        assertNull(json.decodeFromString(KeyboardReplyResponse.serializer(), body).conversationId)
    }

    @Test
    fun `decodes rewrite and translate responses`() {
        val body = """{ "text": "See you again", "usage": ${Payloads.USAGE} }"""
        assertEquals("See you again", json.decodeFromString(KeyboardTextResponse.serializer(), body).text)
    }

    @Test
    fun `decodes me with settings and unlimited pro usage`() {
        val me = json.decodeFromString(MeResponse.serializer(), Payloads.ME)
        assertEquals(Tier.FREE, me.tier)
        assertFalse(me.settings.memoryOptIn)
        assertEquals("linh@example.com", me.user.email)

        val pro = Payloads.ME.replace("\"tier\": \"free\"", "\"tier\": \"pro\"")
            .replace("\"replies_limit\": 20", "\"replies_limit\": null")
        val proMe = json.decodeFromString(MeResponse.serializer(), pro)
        assertEquals(Tier.PRO, proMe.tier)
        assertNull(proMe.usage.repliesLimit)
    }

    @Test
    fun `decodes auth tokens, conversations and memories`() {
        val auth = json.decodeFromString(AuthTokensResponse.serializer(), Payloads.authTokens("a1", "r1"))
        assertEquals("a1", auth.accessToken)
        assertEquals("r1", auth.refreshToken)

        val page = json.decodeFromString(ConversationListResponse.serializer(), Payloads.CONVERSATIONS)
        assertEquals(1, page.total)
        assertEquals("Linh", page.conversations.single().contactLabel)
        assertEquals(12, page.conversations.single().messageCount)

        val memories = json.decodeFromString(ListSerializer(Memory.serializer()), Payloads.MEMORIES)
        assertEquals(2, memories.size)
        assertEquals(0.92, memories[0].confidence)
        assertNull(memories[1].contactLabel)
        assertEquals("pending_review", memories[1].status)
    }

    @Test
    fun `settings response decodes with and without the optional deleted counts`() {
        val plain = json.decodeFromString(SettingsResponse.serializer(), """{"settings":{"memory_opt_in":true}}""")
        assertNull(plain.deleted)

        val withCounts = json.decodeFromString(
            SettingsResponse.serializer(),
            """{"settings":{"memory_opt_in":false},"deleted":{"conversations":3,"memories":5}}""",
        )
        assertFalse(withCounts.settings.memoryOptIn)
        assertEquals(3, withCounts.deleted?.conversations)
        assertEquals(5, withCounts.deleted?.memories)
    }

    @Test
    fun `secret-bearing DTOs never print their secrets`() {
        val printed = listOf(
            SignupRequest("a@b.co", "pw-secret", "Linh"),
            LoginRequest("a@b.co", "pw-secret"),
            RefreshRequest("rt-secret"),
            LogoutRequest("rt-secret"),
            RefreshResponse("at-secret", "rt-secret"),
            json.decodeFromString(AuthTokensResponse.serializer(), Payloads.authTokens("at-secret", "rt-secret")),
        ).joinToString { it.toString() }

        assertFalse(printed.contains("secret"), printed)
    }

    @Test
    fun `unknown response fields are ignored`() {
        val body = Payloads.ME.replace("\"tier\": \"free\",", "\"tier\": \"free\", \"new_field\": 1,")
        assertEquals(Tier.FREE, json.decodeFromString(MeResponse.serializer(), body).tier)
    }
}
