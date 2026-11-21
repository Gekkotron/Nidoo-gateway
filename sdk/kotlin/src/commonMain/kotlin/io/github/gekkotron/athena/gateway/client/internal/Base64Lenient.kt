package io.github.gekkotron.athena.gateway.client.internal

import kotlin.io.encoding.Base64

private val decoder = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/**
 * Decodes base64 as leniently as okio's `decodeBase64()` did before the SDK went multiplatform:
 * whitespace is ignored, the URL-safe alphabet is accepted and padding is optional. Null when invalid.
 */
internal fun String.decodeBase64Lenient(): ByteArray? {
    val clean = buildString(length) {
        for (c in this@decodeBase64Lenient) {
            when (c) {
                ' ', '\n', '\r', '\t' -> Unit
                '-' -> append('+')
                '_' -> append('/')
                else -> append(c)
            }
        }
    }
    return try {
        decoder.decode(clean)
    } catch (e: IllegalArgumentException) {
        null
    }
}

internal fun ByteArray.encodeBase64(): String = Base64.encode(this)
