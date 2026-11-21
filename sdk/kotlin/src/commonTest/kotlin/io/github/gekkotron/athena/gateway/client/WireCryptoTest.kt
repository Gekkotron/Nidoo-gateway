package io.github.gekkotron.athena.gateway.client

import io.github.gekkotron.athena.gateway.client.internal.WireCrypto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.gekkotron.athena.gateway.client.internal.decodeBase64Lenient
import io.github.gekkotron.athena.gateway.client.internal.encodeBase64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WireCryptoTest {
    // Produced by the Python server's AESGCM with key 11…11, nonce 00…0b,
    // plaintext {"a": 1, "s": "hé"}.
    private val pythonVector = "AAECAwQFBgcICQoLaMHXMUGDBNg0mqC9/o/VTpXUMFhwTHBSqWbvoS4ZKc5eNqRSVPypxA=="

    private val countingNonce = { ByteArray(12) { it.toByte() } }

    @Test fun `opens a payload sealed by the Python server`() {
        assertEquals(buildJsonObject { put("a", 1); put("s", "hé") }, testCrypto.open(pythonVector))
    }

    @Test fun `seal puts the 12-byte nonce first`() {
        val crypto = WireCrypto(ByteArray(32) { 0x11 }, countingNonce)
        assertTrue(crypto.seal(buildJsonObject { put("a", 1) }).startsWith("AAECAwQFBgcICQoL"))
    }

    @Test fun `round trip and fresh nonce per call`() {
        val payload = buildJsonObject { put("x", "y") }
        val a = testCrypto.seal(payload)
        val b = testCrypto.seal(payload)
        assertNotEquals(a, b)
        assertEquals(payload, testCrypto.open(a))
    }

    @Test fun `tampered ciphertext is rejected`() {
        val bytes = pythonVector.decodeBase64Lenient()!!
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val tampered = bytes.encodeBase64()
        assertFailsWith<Exception> { testCrypto.open(tampered) }
    }

    @Test fun `short or non-base64 input is rejected`() {
        assertFailsWith<IllegalArgumentException> { testCrypto.open("AAAA") }
        assertFailsWith<IllegalArgumentException> { testCrypto.open("not base64 !!") }
    }

    @Test fun `fromHex validates length and characters`() {
        assertFailsWith<IllegalArgumentException> { WireCrypto.fromHex("11".repeat(31)) }
        assertFailsWith<IllegalArgumentException> { WireCrypto.fromHex("zz".repeat(32)) }
        WireCrypto.fromHex("aB".repeat(32)) // mixed case is fine
    }
}
