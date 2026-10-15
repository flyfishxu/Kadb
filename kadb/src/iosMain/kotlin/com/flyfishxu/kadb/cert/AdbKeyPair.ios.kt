@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flyfishxu.kadb.cert

import com.flyfishxu.kadb.core.AdbMessage
import com.flyfishxu.kadb.nativebridge.*
import okio.ByteString.Companion.toByteString

internal actual class AdbKeyPair internal constructor(
    internal val privateKeyPem: ByteArray,
    internal val certificatePem: ByteArray,
    private val privateKeyDer: ByteArray,
    private val certificateDer: ByteArray,
    private val publicKeyDer: ByteArray
) {
    actual fun signPayload(message: AdbMessage): ByteArray = withNativeIdentity(privateKeyDer) { identity ->
        require(message.payloadLength == 20 && message.payload.size >= 20) { "ADB AUTH token must be 20 bytes" }
        ByteArray(256).also { output ->
            val ok = message.payload.withNativeBytes { token, _ ->
                output.withNativeBytes { bytes, _ -> kadb_identity_sign_token(identity, token, 20u, bytes) }
            }
            check(ok == 1) { "Failed to sign ADB AUTH token" }
        }
    }

    actual fun encodeAdbPublicKey(name: String): ByteArray = withNativeIdentity(privateKeyDer) { identity ->
        val encoded = ByteArray(524)
        check(encoded.withNativeBytes { output, _ -> kadb_identity_adb_public_key(identity, output) } == 1) {
            "Failed to encode RSA-2048 ADB public key"
        }
        encodeAdbPublicKeyWithName(encoded, name)
    }

    actual fun publicKeyFingerprint(): String = publicKeyDer.toByteString().sha256().base64()

    internal fun adbTlsFingerprintHex(): String = publicKeyDer.toByteString().sha256().hex().uppercase()

    actual fun isCertificateValid(): Boolean = withNativeIdentity(privateKeyDer) { identity ->
        certificateDer.withNativeBytes { bytes, length -> kadb_identity_set_certificate(identity, bytes, length) } == 1 &&
            kadb_identity_certificate_valid(identity) == 1
    }
}
