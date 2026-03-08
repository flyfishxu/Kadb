package com.flyfishxu.kadb.cert

import java.security.PrivateKey

sealed class KadbCertException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class PrivateKeyParseFailed(cause: Throwable? = null) : KadbCertException("Failed to parse private key", cause)
    class StoreReadFailed(cause: Throwable? = null) : KadbCertException("Failed to read identity from store", cause)
    class StoreWriteFailed(cause: Throwable? = null) : KadbCertException("Failed to write identity to store", cause)
    class PolicyViolation(message: String, cause: Throwable? = null) : KadbCertException(message, cause)

    companion object {
        internal fun fromPrivateKeyParseError(error: Throwable): KadbCertException {
            return if (error is KadbCertException) error else PrivateKeyParseFailed(error)
        }
    }
}
