package com.flyfishxu.kadb.cert

import com.flyfishxu.kadb.core.AdbMessage

/** Platform-owned keys and certificate; common code uses ADB operations only. */
internal expect class AdbKeyPair {
    fun signPayload(message: AdbMessage): ByteArray
    fun encodeAdbPublicKey(name: String): ByteArray
    fun publicKeyFingerprint(): String
    fun isCertificateValid(): Boolean
}
