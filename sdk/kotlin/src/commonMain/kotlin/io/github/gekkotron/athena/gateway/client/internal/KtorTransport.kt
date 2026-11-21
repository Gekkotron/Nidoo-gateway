package io.github.gekkotron.athena.gateway.client.internal

import io.github.gekkotron.athena.gateway.client.GatewayException
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.io.IOException

/**
 * [Transport] over Ktor: the iOS transport (Darwin engine). The JVM keeps [Transport]'s OkHttp
 * implementation so its consumers stay on OkHttp 4.
 *
 * Redirects are never followed: the gateway has no reason to send one, and following it could
 * downgrade the request to cleartext.
 *
 * @param defaultSocketTimeoutMillis the engine's own read timeout, if known; a per-call
 *   `readTimeoutSeconds` never lowers it.
 */
internal class KtorTransport(
    baseUrl: String,
    engine: HttpClientEngine,
    private val defaultSocketTimeoutMillis: Long? = null,
) : Transport {
    private val base: URLBuilder = parseBase(baseUrl)

    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }

    internal fun urlFor(path: String): String =
        URLBuilder(base.build()).apply {
            // Drop the empty segment a trailing slash leaves, then add the path's own segments.
            val segments = pathSegments.filter { it.isNotEmpty() }
            pathSegments = segments
            appendPathSegments(path.split('/').filter { it.isNotEmpty() })
        }.buildString()

    override suspend fun post(path: String, body: String, readTimeoutSeconds: Int?): RawResponse = transportCall {
        val response = client.post(urlFor(path)) {
            setBody(ByteArrayContent(body.encodeToByteArray(), ContentType.Application.OctetStream))
            if (readTimeoutSeconds != null) {
                val millis = maxOf(readTimeoutSeconds * 1000L, defaultSocketTimeoutMillis ?: 0L)
                timeout { socketTimeoutMillis = millis }
            }
        }
        response.toRaw()
    }

    override fun stream(path: String, body: String): Flow<StreamItem> = channelFlow {
        transportCall {
            client.preparePost(urlFor(path)) {
                header(HttpHeaders.Accept, "text/event-stream")
                setBody(ByteArrayContent(body.encodeToByteArray(), ContentType.Application.OctetStream))
                // Streams sit idle between messages; the server sends a keepalive every 15 s.
                timeout {
                    socketTimeoutMillis = STREAM_SOCKET_TIMEOUT_MILLIS
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }.execute { response ->
                if (response.contentType()?.match(ContentType.Text.EventStream) != true) {
                    send(StreamItem.NotAStream(response.toRaw()))
                } else {
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readLine() ?: break
                        if (line.startsWith("data:")) send(StreamItem.Data(line.substring(5).trim()))
                    }
                }
            }
        }
    }

    private suspend fun HttpResponse.toRaw(): RawResponse =
        RawResponse(status.value, headers[HttpHeaders.ContentType], bodyAsText())

    private companion object {
        const val STREAM_SOCKET_TIMEOUT_MILLIS = 45_000L

        fun parseBase(baseUrl: String): URLBuilder {
            val builder = try {
                URLBuilder(baseUrl)
            } catch (e: Exception) {
                throw IllegalArgumentException("baseUrl is not a valid URL", e)
            }
            // URLBuilder fills in http://localhost for anything without a scheme, so check the input itself.
            require("://" in baseUrl && builder.protocol in setOf(URLProtocol.HTTPS, URLProtocol.HTTP)) {
                "baseUrl must be an http(s) URL"
            }
            require(builder.host.isNotEmpty()) { "baseUrl has no host" }
            return builder
        }

        /** Engine failures (a Darwin NSError, a timeout, a reset) are worth retrying: [GatewayException.Transport]. */
        suspend fun <T> transportCall(block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            throw e
        } catch (e: IOException) {
            throw GatewayException.Transport(e)
        } catch (e: Exception) {
            throw GatewayException.Transport(IOException(e.message ?: e::class.simpleName ?: "I/O error", e))
        }
    }
}
