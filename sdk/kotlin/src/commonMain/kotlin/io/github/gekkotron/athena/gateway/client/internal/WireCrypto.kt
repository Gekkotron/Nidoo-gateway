package io.github.gekkotron.athena.gateway.client.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The gateway wire format: base64(nonce[12] ‖ AES-256-GCM ciphertext+tag) of a JSON object. */
internal class WireCrypto(
    key: ByteArray,
    private val nonceSource: () -> ByteArray = { secureRandomBytes(NONCE_BYTES) },
) {
    init {
        require(key.size == 32) { "secretKey must be 64 hex characters (32 bytes)" }
    }

    private val key = key.copyOf()

    fun seal(payload: JsonObject): String {
        val nonce = nonceSource()
        return (nonce + platformAesGcmSeal(key, nonce, payload.toString().encodeToByteArray())).encodeBase64()
    }

    fun open(data: String): JsonObject {
        val raw = requireNotNull(data.trim().decodeBase64Lenient()) { "response is not base64" }
        require(raw.size >= NONCE_BYTES + TAG_BYTES) { "response is too short" }
        val plaintext = platformAesGcmOpen(key, raw.copyOfRange(0, NONCE_BYTES), raw.copyOfRange(NONCE_BYTES, raw.size))
        return Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
    }

    companion object {
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16

        fun fromHex(hex: String): WireCrypto {
            require(hex.length == 64 && hex.all { it.digitToIntOrNull(16) != null }) {
                "secretKey must be 64 hex characters (32 bytes)"
            }
            return WireCrypto(ByteArray(32) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() })
        }
    }
}

/** AES-256-GCM seal: returns ciphertext‖tag[16]. JVM: javax.crypto; iOS: CryptoKit. */
internal expect fun platformAesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray

/** AES-256-GCM open; throws when the tag does not verify. */
internal expect fun platformAesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray

internal expect fun secureRandomBytes(size: Int): ByteArray
