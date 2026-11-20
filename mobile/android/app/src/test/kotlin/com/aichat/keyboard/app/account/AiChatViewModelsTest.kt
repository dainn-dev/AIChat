package com.aichat.keyboard.app.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.keyboard.core.network.AiChatClient
import com.aichat.keyboard.core.network.AiChatClientFactory
import com.aichat.keyboard.core.network.auth.AuthTokens
import com.aichat.keyboard.core.network.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ViewModels against a real [AiChatClient] talking to MockWebServer. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelsTest {
    private class InMemoryTokenStore(initial: AuthTokens?) : TokenStore {
        private val state = MutableStateFlow(initial)
        override val tokens: StateFlow<AuthTokens?> get() = state
        @Synchronized override fun current() = state.value
        @Synchronized override fun save(tokens: AuthTokens) { state.value = tokens }
        @Synchronized override fun clear() { state.value = null }
        @Synchronized override fun compareAndSet(expected: AuthTokens?, new: AuthTokens?): Boolean {
            if (state.value != expected) return false
            state.value = new
            return true
        }
    }

    private lateinit var server: MockWebServer
    private val viewModels = mutableListOf<ViewModel>()
    private val tokens = AuthTokens("access", "refresh")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun client(store: TokenStore): AiChatClient =
        AiChatClientFactory.create(server.url("/").toString(), store, AiChatClientFactory.defaultHttpClient(), Dispatchers.IO)

    private fun <T : ViewModel> track(viewModel: T): T = viewModel.also { viewModels += it }

    private fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(5_000) { first(predicate) } }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private val user = """{"id":"u1","email":"linh@example.com","display_name":null,"tier":"free","created_at":"2026-11-01T08:00:00.000Z"}"""
    private val usage = """{"replies_used":1,"replies_limit":20,"screenshots_used":0,"screenshots_limit":5}"""
    private val me = """{"user":$user,"tier":"free","usage":$usage,"settings":{"memory_opt_in":true}}"""
    private val conversations = """{"conversations":[{"id":"c1","platform":"zalo","contact_label":"Linh","source":"keyboard",
        "message_count":3,"updated_at":"2026-11-20T09:15:00.000Z"}],"total":1,"limit":20,"offset":0}"""

    // -- AuthViewModel -----------------------------------------------------------------------------------------------

    @Test
    fun `double submit sends a single login request and clears the password afterwards`() {
        val store = InMemoryTokenStore(null)
        server.enqueue(
            json("""{"user":$user,"access_token":"a","refresh_token":"r"}""").setHeadersDelay(300, TimeUnit.MILLISECONDS),
        )
        val viewModel = track(AuthViewModel(client(store)))
        viewModel.setEmail("linh@example.com")
        viewModel.setPassword("hunter22!")

        viewModel.submit()
        viewModel.submit()
        val done = viewModel.state.await { !it.submitting }

        assertEquals(1, server.requestCount)
        assertEquals(AuthTokens("a", "r"), store.current())
        assertEquals("", done.password)
    }

    @Test
    fun `an invalid form sends nothing and shows field errors`() {
        val viewModel = track(AuthViewModel(client(InMemoryTokenStore(null))))
        viewModel.setEmail("not-an-email")

        viewModel.submit()

        assertTrue(viewModel.state.value.attempted)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `toggling the mode keeps the email and clears the confirmation`() {
        val viewModel = track(AuthViewModel(client(InMemoryTokenStore(null))))
        viewModel.setEmail("linh@example.com")
        viewModel.toggleMode()
        viewModel.setConfirm("x")

        viewModel.toggleMode()

        assertEquals("linh@example.com", viewModel.state.value.email)
        assertEquals("", viewModel.state.value.confirm)
    }

    // -- AccountViewModel --------------------------------------------------------------------------------------------

    @Test
    fun `a late me result does not land after sign-out`() {
        val store = InMemoryTokenStore(tokens)
        server.enqueue(json(me).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val viewModel = track(AccountViewModel(client(store)))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) // GET /me is in flight

        store.clear()
        viewModel.state.await { !it.loading }
        Thread.sleep(800) // past the delayed response

        assertNull(viewModel.state.value.me)
        assertEquals(false, viewModel.isSignedIn.value)
    }

    @Test
    fun `turning memory off reports the deleted counts`() {
        val store = InMemoryTokenStore(tokens)
        server.enqueue(json(me))
        server.enqueue(json("""{"settings":{"memory_opt_in":false},"deleted":{"conversations":3,"memories":5}}"""))
        val viewModel = track(AccountViewModel(client(store)))
        viewModel.state.await { it.me != null }

        viewModel.setMemoryOptIn(false)
        val state = viewModel.state.await { !it.savingOptIn && it.optOutResult != null }

        assertEquals(AccountViewModel.OptOutResult(3, 5), state.optOutResult)
        assertEquals(false, state.me?.settings?.memoryOptIn)
    }

    @Test
    fun `turning memory off without counts from the backend still confirms`() {
        val store = InMemoryTokenStore(tokens)
        server.enqueue(json(me))
        server.enqueue(json("""{"settings":{"memory_opt_in":false}}"""))
        val viewModel = track(AccountViewModel(client(store)))
        viewModel.state.await { it.me != null }

        viewModel.setMemoryOptIn(false)

        assertEquals(
            AccountViewModel.OptOutResult(null, null),
            viewModel.state.await { it.optOutResult != null }.optOutResult,
        )
    }

    // -- Conversations / Memories ------------------------------------------------------------------------------------

    @Test
    fun `a forced sign-out clears the conversations and asks the screen to leave`() {
        val store = InMemoryTokenStore(tokens)
        server.enqueue(json(conversations))
        val viewModel = track(ConversationsViewModel(client(store)))
        assertEquals(1, viewModel.state.await { it.loaded }.items.size)

        store.clear()
        val state = viewModel.state.await { it.signedOut }

        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `deleting a conversation that is already gone (404) removes it without an error`() {
        val store = InMemoryTokenStore(tokens)
        server.enqueue(json(conversations))
        server.enqueue(
            MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"CONVERSATION_NOT_FOUND","message":"Conversation not found."}}"""),
        )
        val viewModel = track(ConversationsViewModel(client(store)))
        viewModel.state.await { it.loaded }

        viewModel.delete("c1")
        val state = viewModel.state.await { it.deletingIds.isEmpty() && it.items.isEmpty() }

        assertNull(state.error)
        assertEquals(0, state.total)
    }

    @Test
    fun `a forced sign-out clears the memories`() {
        val store = InMemoryTokenStore(tokens)
        server.enqueue(
            json(
                """[{"id":"m1","kind":"fact","content":"Likes tea","contact_label":"Linh","status":"active",
                "confidence":0.9,"source":"extracted","source_ref":null,"created_at":"2026-11-20T09:16:00.000Z"}]""",
            ),
        )
        val viewModel = track(MemoriesViewModel(client(store)))
        assertEquals(1, viewModel.state.await { it.loaded }.items.size)

        store.clear()

        assertTrue(viewModel.state.await { it.signedOut }.items.isEmpty())
    }
}
