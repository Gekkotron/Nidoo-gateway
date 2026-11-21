package io.github.gekkotron.athena.gateway.client.internal

import kotlinx.coroutines.flow.Flow

internal sealed interface StreamItem {
    /** One SSE `data:` payload (an encrypted frame). */
    data class Data(val data: String) : StreamItem

    /** The server answered without an event stream (a rejection); body read whole. */
    data class NotAStream(val response: RawResponse) : StreamItem
}

internal interface Transport {
    /**
     * @param readTimeoutSeconds when set, raises the read timeout for this call alone (never
     *   lowers it below the client's own configured read timeout).
     * @throws GatewayException.Transport on I/O failure.
     */
    suspend fun post(path: String, body: String, readTimeoutSeconds: Int? = null): RawResponse

    /** Cold flow; each collection opens one connection. Ends when the server closes it. */
    fun stream(path: String, body: String): Flow<StreamItem>
}
