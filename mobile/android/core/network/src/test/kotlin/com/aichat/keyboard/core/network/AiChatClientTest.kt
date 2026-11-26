package com.aichat.keyboard.core.network

import com.aichat.keyboard.core.network.auth.AuthTokens
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.KeyboardReplyRequest
import com.aichat.keyboard.core.network.model.MessageSender
import com.aichat.keyboard.core.network.model.OutputLang
import com.aichat.keyboard.core.network.model.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiChatClientTest {
    private lateinit var server: MockWebServer
    private val oldTokens = AuthTokens("access-old", "refresh-old")
    private val newTokens = AuthTokens("access-new", "refresh-new")

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun client(store: InMemoryTokenStore): AiChatClient {
        val http = AiChatClientFactory.defaultHttpClient().newBuilder()
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        return AiChatClientFactory.create(server.url("/").toString(), store, http, Dispatchers.IO)
    }

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject

    // -- Login / signup / logout ------------------------------------------------------------------------------

    @Test
    fun `login posts credentials and persists both tokens`() = runBlocking {
        val store = InMemoryTokenStore()
        server.enqueue(jsonResponse(200, Payloads.authTokens("a1", "r1")))

        val result = client(store).login(" linh@example.com ", "hunter22!")

        assertIs<ApiResult.Success<*>>(result)
        assertEquals(AuthTokens("a1", "r1"), store.current())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/login", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = request.json()
        assertEquals("\"linh@example.com\"", body["email"].toString())
        assertEquals("\"hunter22!\"", body["password"].toString())
    }

    @Test
    fun `wrong credentials map to Unauthorized and never trigger a refresh`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens) // even with an old session around
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Invalid email or password."))

        val result = client(store).login("linh@example.com", "wrong")

        assertEquals(ApiResult.Failure(ApiError.Unauthorized("Invalid email or password.")), result)
        assertEquals(1, server.requestCount)
        assertEquals(oldTokens, store.current())
    }

    @Test
    fun `signup with a taken email maps to Conflict`() = runBlocking {
        val store = InMemoryTokenStore()
        server.enqueue(errorResponse(409, "CONFLICT", "An account with this email already exists."))

        val result = client(store).signup("linh@example.com", "password123")

        assertIs<ApiError.Conflict>((result as ApiResult.Failure).error)
        assertNull(store.current())
        assertEquals("/auth/signup", server.takeRequest().path)
    }

    @Test
    fun `logout revokes the refresh token and clears the session`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(ApiResult.Success(Unit), client(store).logout())

        val request = server.takeRequest()
        assertEquals("/auth/logout", request.path)
        assertEquals("\"refresh-old\"", request.json()["refresh_token"].toString())
        assertNull(store.current())
    }

    @Test
    fun `logout clears the session even when the server is unreachable`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(ApiResult.Success(Unit), client(store).logout())
        assertNull(store.current())
    }

    @Test
    fun `cancelling logout while the revoke call is in flight still leaves the user signed out`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(5, TimeUnit.SECONDS))
        val client = client(store)

        val job = launch(Dispatchers.IO) { client.logout() }
        assertEquals("/auth/logout", server.takeRequest(5, TimeUnit.SECONDS)?.path) // revoke is in flight
        job.cancelAndJoin()

        assertNull(store.current())
        assertEquals(false, client.isSignedIn.first())
    }

    // -- Bearer token and refresh ------------------------------------------------------------------------------

    @Test
    fun `protected calls send the bearer token`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(jsonResponse(200, Payloads.ME))

        val result = client(store).me()

        assertIs<ApiResult.Success<*>>(result)
        assertEquals("Bearer access-old", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `signed out calls fail fast without touching the network`() = runBlocking {
        val result = client(InMemoryTokenStore()).me()

        assertIs<ApiError.Unauthorized>((result as ApiResult.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a 401 refreshes once, persists the rotated tokens and retries`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Unauthorized"))
        server.enqueue(jsonResponse(200, Payloads.refresh("access-new", "refresh-new")))
        server.enqueue(jsonResponse(200, Payloads.ME))

        val result = client(store).me()

        assertIs<ApiResult.Success<*>>(result)
        assertEquals(newTokens, store.current())

        val first = server.takeRequest()
        assertEquals("/me", first.path)
        assertEquals("Bearer access-old", first.getHeader("Authorization"))

        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.path)
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("\"refresh-old\"", refresh.json()["refresh_token"].toString())

        val retry = server.takeRequest()
        assertEquals("/me", retry.path)
        assertEquals("Bearer access-new", retry.getHeader("Authorization"))
    }

    @Test
    fun `concurrent 401s trigger exactly one refresh`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        val refreshCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    // Slow refresh, so the other requests pile up on the lock while it is in flight.
                    jsonResponse(200, Payloads.refresh("access-new", "refresh-new"))
                        .setBodyDelay(300, TimeUnit.MILLISECONDS)
                }
                request.getHeader("Authorization") == "Bearer access-new" -> jsonResponse(200, Payloads.ME)
                else -> errorResponse(401, "UNAUTHORIZED", "Unauthorized")
            }
        }
        val client = client(store)

        val results = (1..8).map { async(Dispatchers.IO) { client.me() } }.awaitAll()

        assertTrue(results.all { it is ApiResult.Success }, "all calls succeed: $results")
        assertEquals(1, refreshCalls.get())
        assertEquals(newTokens, store.current())
    }

    @Test
    fun `a rejected refresh signs the user out`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Unauthorized"))
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Invalid or expired refresh token."))

        val client = client(store)
        assertEquals(true, client.isSignedIn.first())
        val result = client.me()

        assertIs<ApiError.Unauthorized>((result as ApiResult.Failure).error)
        assertNull(store.current())
        assertEquals(2, server.requestCount) // no retry after the rejected refresh
        // A later call fails locally instead of hammering the server.
        assertIs<ApiError.Unauthorized>((client.me() as ApiResult.Failure).error)
        assertEquals(2, server.requestCount)
        assertEquals(false, client.isSignedIn.first())
    }

    @Test
    fun `a 400 from refresh, e g from a proxy, keeps the session`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Unauthorized"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("<html>Bad Request</html>"))

        val result = client(store).me()

        assertIs<ApiError.Unauthorized>((result as ApiResult.Failure).error)
        assertEquals(oldTokens, store.current())
    }

    @Test
    fun `logout during an in-flight refresh is not undone by the refresh result`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        val refreshArrived = CountDownLatch(1)
        val newTokenUsed = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/refresh" -> {
                    refreshArrived.countDown()
                    jsonResponse(200, Payloads.refresh("access-new", "refresh-new"))
                        .setHeadersDelay(500, TimeUnit.MILLISECONDS)
                }
                "/auth/logout" -> MockResponse().setResponseCode(204)
                else -> {
                    if (request.getHeader("Authorization") == "Bearer access-new") newTokenUsed.incrementAndGet()
                    errorResponse(401, "UNAUTHORIZED", "Unauthorized")
                }
            }
        }
        val client = client(store)

        val me = async(Dispatchers.IO) { client.me() }
        assertTrue(refreshArrived.await(5, TimeUnit.SECONDS))
        client.logout()
        val result = me.await()

        assertIs<ApiError.Unauthorized>((result as ApiResult.Failure).error)
        assertNull(store.current(), "the rotated tokens must not resurrect the session")
        assertEquals(0, newTokenUsed.get(), "the request must not be retried after logout")
    }

    @Test
    fun `a transient refresh failure keeps the session`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Unauthorized"))
        server.enqueue(errorResponse(503, "HTTP_503", "Service unavailable"))

        val result = client(store).me()

        assertIs<ApiError.Unauthorized>((result as ApiResult.Failure).error)
        assertEquals(oldTokens, store.current())
    }

    @Test
    fun `a 401 after a successful refresh is not retried again`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Unauthorized"))
        server.enqueue(jsonResponse(200, Payloads.refresh("access-new", "refresh-new")))
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Unauthorized"))

        val result = client(store).me()

        assertIs<ApiError.Unauthorized>((result as ApiResult.Failure).error)
        assertEquals(3, server.requestCount)
        assertEquals(newTokens, store.current())
    }

    // -- Error mapping over HTTP -------------------------------------------------------------------------------

    private val replyRequest = KeyboardReplyRequest(
        conversation = listOf(ChatMessage(MessageSender.THEM, "tối nay đi ăn không?")),
        tone = Tone.FRIENDLY,
        platform = "zalo",
        contactLabel = "Linh",
        lang = OutputLang.VI,
        remember = false,
    )

    @Test
    fun `reply success decodes suggestions and sends the contract fields`() = runBlocking {
        server.enqueue(jsonResponse(200, Payloads.REPLY))

        val result = client(InMemoryTokenStore(oldTokens)).reply(replyRequest)

        assertEquals(1, (result as ApiResult.Success).value.suggestions.size)
        val request = server.takeRequest()
        assertEquals("/v1/keyboard/reply", request.path)
        val body = request.json()
        assertEquals("\"vi\"", body["lang"].toString())
        assertEquals("\"Linh\"", body["contact_label"].toString())
        assertEquals("\"zalo\"", body["platform"].toString())
        assertEquals("false", body["remember"].toString())
    }

    @Test
    fun `reply without sticker_tags decodes them as empty`() = runBlocking {
        server.enqueue(jsonResponse(200, Payloads.REPLY))

        val result = client(InMemoryTokenStore(oldTokens)).reply(replyRequest)

        assertEquals(emptyList(), (result as ApiResult.Success).value.stickerTags)
    }

    @Test
    fun `reply with sticker_tags decodes them, unknown names included`() = runBlocking {
        server.enqueue(jsonResponse(200, Payloads.replyWithStickerTags("""["thanks","love","unicorn"]""")))

        val result = client(InMemoryTokenStore(oldTokens)).reply(replyRequest)

        val value = (result as ApiResult.Success).value
        assertEquals(listOf("thanks", "love", "unicorn"), value.stickerTags)
        assertEquals(1, value.suggestions.size)
    }

    @Test
    fun `a null or malformed sticker_tags never fails the reply`() = runBlocking {
        for (tags in listOf("null", "\"thanks\"", "{}", """["ok", 3, null, {"a":1}]""")) {
            server.enqueue(jsonResponse(200, Payloads.replyWithStickerTags(tags)))

            val result = client(InMemoryTokenStore(oldTokens)).reply(replyRequest)

            val expected = if (tags.startsWith("[")) listOf("ok") else emptyList()
            assertEquals(expected, (result as ApiResult.Success).value.stickerTags, tags)
        }
    }

    @Test
    fun `reply 429 maps to QuotaExceeded with the limit`() = runBlocking {
        server.enqueue(
            errorResponse(
                429,
                "QUOTA_EXCEEDED",
                "Daily reply limit reached. Upgrade to Pro for more.",
                """{"metric":"reply","limit":20,"scope":"daily"}""",
            ),
        )

        val error = (client(InMemoryTokenStore(oldTokens)).reply(replyRequest) as ApiResult.Failure).error

        assertIs<ApiError.QuotaExceeded>(error)
        assertEquals(20, error.limit)
    }

    @Test
    fun `reply 402 spend cap maps to QuotaExceeded`() = runBlocking {
        server.enqueue(
            errorResponse(
                402,
                "SPEND_CAP_EXCEEDED",
                "Daily spend cap reached. Please try again tomorrow.",
                """{"scope":"daily","cap_usd":2.5}""",
            ),
        )

        val error = (client(InMemoryTokenStore(oldTokens)).reply(replyRequest) as ApiResult.Failure).error

        assertEquals(
            ApiError.QuotaExceeded("Daily spend cap reached. Please try again tomorrow.", limit = null, metric = "spend_cap"),
            error,
        )
    }

    @Test
    fun `a redirect is not followed, so credentials are never replayed elsewhere`() = runBlocking {
        val store = InMemoryTokenStore()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))

        val result = client(store).login("linh@example.com", "hunter22!")

        assertIs<ApiResult.Failure>(result)
        assertEquals(1, server.requestCount)
        assertNull(store.current())
    }

    @Test
    fun `reply 502 maps to Upstream`() = runBlocking {
        server.enqueue(errorResponse(502, "UPSTREAM_ERROR", "AI provider unavailable"))

        val error = (client(InMemoryTokenStore(oldTokens)).reply(replyRequest) as ApiResult.Failure).error

        assertEquals(ApiError.Upstream("AI provider unavailable", 502), error)
    }

    @Test
    fun `settings 400 maps to Validation`() = runBlocking {
        server.enqueue(
            errorResponse(
                400,
                "BAD_REQUEST",
                "Request validation failed",
                """{"fields":["memory_opt_in must be a boolean value"]}""",
            ),
        )

        val error = (client(InMemoryTokenStore(oldTokens)).setMemoryOptIn(true) as ApiResult.Failure).error

        assertEquals(ApiError.Validation("Request validation failed", listOf("memory_opt_in must be a boolean value")), error)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/me/settings", request.path)
        assertEquals("""{"memory_opt_in":true}""", request.body.readUtf8())
    }

    @Test
    fun `a dropped connection maps to Network`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val error = (client(InMemoryTokenStore(oldTokens)).me() as ApiResult.Failure).error

        assertIs<ApiError.Network>(error)
    }

    @Test
    fun `an unreachable server maps to Network`() = runBlocking {
        val client = client(InMemoryTokenStore(oldTokens))
        server.shutdown()

        assertIs<ApiError.Network>((client.me() as ApiResult.Failure).error)
    }

    // -- Conversations and memories ----------------------------------------------------------------------------

    @Test
    fun `conversations are paginated`() = runBlocking {
        server.enqueue(jsonResponse(200, Payloads.CONVERSATIONS))

        val page = (client(InMemoryTokenStore(oldTokens)).conversations(limit = 20, offset = 40) as ApiResult.Success).value

        assertEquals(1, page.conversations.size)
        assertEquals("/conversations?limit=20&offset=40", server.takeRequest().path)
    }

    @Test
    fun `deleting a conversation returns Success on 204 and NotFound on 404`() = runBlocking {
        val client = client(InMemoryTokenStore(oldTokens))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(errorResponse(404, "CONVERSATION_NOT_FOUND", "Conversation not found."))

        assertEquals(ApiResult.Success(Unit), client.deleteConversation("0d6f1f4a-9c11-4b8a-9a52-7b0c4a3e2f4e"))
        assertIs<ApiError.NotFound>((client.deleteConversation("missing") as ApiResult.Failure).error)

        val first = server.takeRequest()
        assertEquals("DELETE", first.method)
        assertEquals("/conversations/0d6f1f4a-9c11-4b8a-9a52-7b0c4a3e2f4e", first.path)
    }

    @Test
    fun `memories are listed and deleted`() = runBlocking {
        val client = client(InMemoryTokenStore(oldTokens))
        server.enqueue(jsonResponse(200, Payloads.MEMORIES))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(2, (client.memories() as ApiResult.Success).value.size)
        assertEquals(ApiResult.Success(Unit), client.deleteMemory("a1b2c3d4-0000-4000-8000-000000000001"))

        assertEquals("/memories", server.takeRequest().path)
        assertEquals("/memories/a1b2c3d4-0000-4000-8000-000000000001", server.takeRequest().path)
    }

    @Test
    fun `a delete that needs a refresh is retried with the new token`() = runBlocking {
        val store = InMemoryTokenStore(oldTokens)
        server.enqueue(errorResponse(401, "UNAUTHORIZED", "Unauthorized"))
        server.enqueue(jsonResponse(200, Payloads.refresh("access-new", "refresh-new")))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(ApiResult.Success(Unit), client(store).deleteMemory("a1b2c3d4-0000-4000-8000-000000000001"))
        assertEquals(newTokens, store.current())
    }
}
