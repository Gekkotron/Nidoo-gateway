package io.github.gekkotron.athena.gateway.client

import io.github.gekkotron.athena.gateway.client.internal.KtorTransport
import io.github.gekkotron.athena.gateway.client.internal.RawResponse
import io.github.gekkotron.athena.gateway.client.internal.StreamItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtorTransportTest {
    private val octet = headersOf(HttpHeaders.ContentType, "application/octet-stream")
    private val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")

    private fun transport(engine: MockEngine, defaultSocketTimeoutMillis: Long? = null) =
        KtorTransport("https://h.example/", engine, defaultSocketTimeoutMillis)

    private fun HttpRequestData.socketTimeout(): Long? = getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis

    @Test fun urlsWorkWithAndWithoutTrailingSlashAndWithAPrefix() {
        val engine = MockEngine { respond("") }
        assertEquals("https://h.example/gateway", KtorTransport("https://h.example", engine).urlFor("gateway"))
        assertEquals("https://h.example/mqtt/publish", KtorTransport("https://h.example/", engine).urlFor("mqtt/publish"))
        assertEquals("https://h.example/athena/mqtt/subscribe", KtorTransport("https://h.example/athena", engine).urlFor("mqtt/subscribe"))
        assertEquals("https://h.example/athena/gateway", KtorTransport("https://h.example/athena/", engine).urlFor("gateway"))
    }

    @Test fun invalidBaseUrlIsRejectedAtConstruction() {
        val engine = MockEngine { respond("") }
        assertFailsWith<IllegalArgumentException> { KtorTransport("not a url", engine) }
        assertFailsWith<IllegalArgumentException> { KtorTransport("ftp://h.example", engine) }
    }

    @Test fun postSendsTheBodyAsOctetStream() = runTest {
        val engine = MockEngine { respond("ENC", HttpStatusCode.OK, octet) }
        assertEquals(RawResponse(200, "application/octet-stream", "ENC"), transport(engine).post("mqtt/publish", "BODY"))
        val req = engine.requestHistory.single()
        assertEquals("POST", req.method.value)
        assertEquals("https://h.example/mqtt/publish", req.url.toString())
        val body = req.body as OutgoingContent.ByteArrayContent
        assertEquals("BODY", body.bytes().decodeToString())
        assertEquals("application/octet-stream", body.contentType.toString())
    }

    @Test fun engineFailureIsTransport() = runTest {
        // What a Darwin NSURLErrorDomain failure looks like from Kotlin when the app resumes (Review Focus #3).
        val engine = MockEngine { throw IllegalStateException("NSURLErrorDomain -1005") }
        assertFailsWith<GatewayException.Transport> { transport(engine).post("gateway", "B") }
        assertFailsWith<GatewayException.Transport> { transport(engine).stream("mqtt/subscribe", "B").toList() }
    }

    @Test fun redirectIsNotFollowed() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://h.example/gateway")) }
        assertEquals(302, transport(engine).post("gateway", "B").code)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test fun sseDataLinesBecomeItems() = runTest {
        val engine = MockEngine { respond(": keepalive\n\ndata: AAA\n\ndata: BBB\n\n", HttpStatusCode.OK, eventStream) }
        val items = transport(engine).stream("mqtt/subscribe", "B").toList()
        assertEquals(listOf(StreamItem.Data("AAA"), StreamItem.Data("BBB")), items)
        val req = engine.requestHistory.single()
        assertEquals("text/event-stream", req.headers[HttpHeaders.Accept])
        assertEquals(45_000, req.socketTimeout())
    }

    @Test fun nonStreamReplyIsNotAStream() = runTest {
        val engine = MockEngine {
            respond("""{"error":"forbidden"}""", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val items = transport(engine).stream("mqtt/subscribe", "B").toList()
        assertEquals(listOf(StreamItem.NotAStream(RawResponse(403, "application/json", """{"error":"forbidden"}"""))), items)
    }

    @Test fun cancellingTheCollectorClosesAnOpenStream() = runTest {
        val engine = MockEngine {
            val channel = ByteChannel(autoFlush = true)
            backgroundScope.launch {
                channel.writeStringUtf8("data: A\n\n")
                awaitCancellation()
            }
            respond(channel, HttpStatusCode.OK, eventStream)
        }
        // Real time: the engine writes on its own dispatcher, which virtual time does not wait for.
        val first = withContext(Dispatchers.Default) {
            withTimeout(2_000) { transport(engine).stream("mqtt/subscribe", "B").first() }
        }
        assertEquals(StreamItem.Data("A"), first)
    }

    @Test fun readTimeoutSecondsNeverLowersTheDefault() = runTest {
        val engine = MockEngine { respond("ENC", HttpStatusCode.OK, octet) }
        val t = transport(engine, defaultSocketTimeoutMillis = 60_000)
        t.post("gateway", "B", readTimeoutSeconds = 5)
        t.post("gateway", "B", readTimeoutSeconds = 120)
        t.post("gateway", "B")
        assertEquals(listOf<Long?>(60_000, 120_000, null), engine.requestHistory.map { it.socketTimeout() })
    }

    @Test fun readTimeoutSecondsAppliesWithoutADefault() = runTest {
        val engine = MockEngine { respond("ENC", HttpStatusCode.OK, octet) }
        transport(engine).post("gateway", "B", readTimeoutSeconds = 45)
        assertTrue(engine.requestHistory.single().socketTimeout() == 45_000L)
    }
}
