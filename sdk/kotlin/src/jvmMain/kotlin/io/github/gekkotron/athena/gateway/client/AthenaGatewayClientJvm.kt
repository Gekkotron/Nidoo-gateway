package io.github.gekkotron.athena.gateway.client

import io.github.gekkotron.athena.gateway.client.internal.OkHttpTransport
import io.github.gekkotron.athena.gateway.client.internal.WireCrypto
import okhttp3.OkHttpClient

/**
 * @param baseUrl gateway root, e.g. `https://myhost.tailnet-name.ts.net` (a path prefix is allowed).
 * @param secretKey the 64-hex-character secret configured on the gateway.
 * @param okHttpClient bring your own client for certificate pinning, proxies or timeouts.
 * @param clock unix seconds used for request timestamps; override to correct a skewed device
 *   clock (the gateway rejects requests more than 60 s off).
 * @throws IllegalArgumentException if [baseUrl] or [secretKey] is malformed.
 */
public fun AthenaGatewayClient(
    baseUrl: String,
    secretKey: String,
    okHttpClient: OkHttpClient = OkHttpClient(),
    clock: () -> Long = ::unixSeconds,
): AthenaGatewayClient = AthenaGatewayClient(OkHttpTransport(baseUrl, okHttpClient), WireCrypto.fromHex(secretKey), clock = clock)
