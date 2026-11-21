package io.github.gekkotron.athena.gateway.client

import io.github.gekkotron.athena.gateway.client.internal.decodeBase64Lenient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** Parity with okio's decodeBase64(), which the SDK used before going multiplatform. */
class Base64LenientTest {
    private val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0x01)

    @Test fun standardPadded() = assertContentEquals("hi".encodeToByteArray(), "aGk=".decodeBase64Lenient())

    @Test fun paddingIsOptional() = assertContentEquals("hi".encodeToByteArray(), "aGk".decodeBase64Lenient())

    @Test fun whitespaceIsIgnored() = assertContentEquals("hi".encodeToByteArray(), " aG\nk=\r\n".decodeBase64Lenient())

    @Test fun urlSafeAlphabetIsAccepted() {
        assertContentEquals(bytes, "-_8B".decodeBase64Lenient())
        assertContentEquals(bytes, "+/8B".decodeBase64Lenient())
    }

    @Test fun invalidIsNull() = assertNull("not base64 !!".decodeBase64Lenient())
}
