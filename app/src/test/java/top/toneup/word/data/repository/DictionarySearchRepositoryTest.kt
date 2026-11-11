package top.toneup.word.data.repository

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import top.toneup.word.data.gateway.GatewayClientProvider
import top.toneup.word.data.gateway.GatewayConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `/dict`-as-search against a local MockWebServer.
 *
 * Two properties matter: the mapping (an exact entry, or the gateway's already-sorted
 * suggestions) and that this path is a **plain search** — one request, no cascade, no
 * `meaning_cache` (there is no database here at all).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DictionarySearchRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun config() = GatewayConfig(
        baseUrl = server.url("/").toString(),
        key = "test-key",
        dictPath = "/dict",
        enPath = "/en/{word}",
        mtPath = "/v1/chat/completions",
    )

    private fun repository(readTimeoutSeconds: Long = 10L): DictionarySearchRepository {
        val provider = GatewayClientProvider(
            configProvider = { config() },
            json = JsonCodec.json,
            isDebugBuild = false,
            // MockWebServer speaks plain http.
            httpsOnly = false,
            readTimeoutSeconds = readTimeoutSeconds,
        )
        return DefaultDictionarySearchRepository(provider)
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code).setBody(body)

    private fun success(outcome: Outcome<List<DictionaryHit>>): List<DictionaryHit> =
        (outcome as Outcome.Success).value

    // --- mapping ---------------------------------------------------------------------------

    @Test
    fun `an exact entry becomes one hit with the translation lines joined`() = runTest {
        server.enqueue(
            json(
                200,
                """
                {"found":true,"entry":{"word":"hello","phonetic":"hә'lәu",
                 "definition":"n. an expression of greeting",
                 "translation":"interj. 喂, 嘿\n哈喽","pos":"interj"}}
                """.trimIndent(),
            ),
        )

        val hits = success(repository().search("hello"))

        assertThat(hits).hasSize(1)
        assertThat(hits.single().word).isEqualTo("hello")
        assertThat(hits.single().phonetic).isEqualTo("hә'lәu")
        assertThat(hits.single().translation).isEqualTo("interj. 喂, 嘿；哈喽")
        assertThat(server.requestCount).isEqualTo(1)

        val request = server.takeRequest()
        assertThat(request.path).startsWith("/dict?word=hello")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key")
        // Privacy: the key travels in the header only, never in the URL.
        assertThat(request.requestUrl?.queryParameter("key")).isNull()
    }

    @Test
    fun `a miss returns the suggestions in the order the gateway ranked them`() = runTest {
        server.enqueue(
            json(
                200,
                """
                {"found":false,"suggestions":[
                  {"word":"entire","phonetic":"ɪnˈtaɪə","translation":"adj. 全部的\n整个的"},
                  {"word":"enter","phonetic":"ˈentə","translation":"v. 进入"},
                  {"word":"","phonetic":"","translation":"dropped"}
                ]}
                """.trimIndent(),
            ),
        )

        val hits = success(repository().search("ent"))

        assertThat(hits.map { it.word }).containsExactly("entire", "enter").inOrder()
        assertThat(hits.first().translation).isEqualTo("adj. 全部的；整个的")
        assertThat(hits.first().phonetic).isEqualTo("ɪnˈtaɪə")
    }

    @Test
    fun `duplicate suggestion words are collapsed so picker keys stay unique`() = runTest {
        server.enqueue(
            json(
                200,
                """
                {"found":false,"suggestions":[
                  {"word":"entire","phonetic":"ɪnˈtaɪə","translation":"adj. 全部的"},
                  {"word":"entire","phonetic":"ɪnˈtaɪə","translation":"adj. 整个的"},
                  {"word":"enter","phonetic":"ˈentə","translation":"v. 进入"}
                ]}
                """.trimIndent(),
            ),
        )

        val hits = success(repository().search("ent"))

        // First occurrence wins; the LazyColumn can safely key by word.
        assertThat(hits.map { it.word }).containsExactly("entire", "enter").inOrder()
        assertThat(hits.first().translation).isEqualTo("adj. 全部的")
    }

    @Test
    fun `found without an entry falls back to suggestions`() = runTest {
        server.enqueue(
            json(200, """{"found":true,"suggestions":[{"word":"entire","translation":"adj. 全部的"}]}"""),
        )

        val hits = success(repository().search("ent"))

        assertThat(hits.map { it.word }).containsExactly("entire")
    }

    @Test
    fun `a blank query never touches the network`() = runTest {
        val hits = success(repository().search("   "))

        assertThat(hits).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a 404 counts as nothing matched rather than an error`() = runTest {
        server.enqueue(json(404, """{"error":"not found"}"""))

        val outcome = repository().search("zzzz")

        assertThat(outcome.isSuccess).isTrue()
        assertThat(success(outcome)).isEmpty()
    }

    // --- failures --------------------------------------------------------------------------

    @Test
    fun `unauthorized is a configuration error`() = runTest {
        server.enqueue(json(401, """{"error":"unauthorized"}"""))

        val outcome = repository().search("ent")

        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.CONFIG)
    }

    @Test
    fun `timeout is a retryable network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val outcome = repository(readTimeoutSeconds = 1L).search("ent")

        assertThat(outcome.isSuccess).isFalse()
        val error = (outcome as Outcome.Failure).error
        assertThat(error.code).isEqualTo(ErrorCode.NETWORK)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `a server error is a network error`() = runTest {
        server.enqueue(json(500, """{"error":"boom"}"""))

        val outcome = repository().search("ent")

        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.NETWORK)
    }
}
