package io.github.gekkotron.athena.gateway.client.internal

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

private val gcm by lazy { CryptographyProvider.CryptoKit.get(AES.GCM) }

@OptIn(DelicateCryptographyApi::class)
private fun cipher(key: ByteArray) = gcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key).cipher()

@OptIn(DelicateCryptographyApi::class)
internal actual fun platformAesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray =
    cipher(key).encryptWithIvBlocking(nonce, plaintext)

@OptIn(DelicateCryptographyApi::class)
internal actual fun platformAesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray {
    require(sealed.size >= WireCrypto.TAG_BYTES) { "response is too short" }
    return cipher(key).decryptWithIvBlocking(nonce, sealed)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed ($status)" }
    return bytes
}
