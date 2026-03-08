package com.flyfishxu.kadb.cert

data class KadbIdentitySnapshot(
    val privateKeyPem: ByteArray,
    val certificatePem: ByteArray,
    val fingerprintSha256: String,
    val notAfterEpochMillis: Long
) {
    internal fun deepCopy(): KadbIdentitySnapshot {
        return copy(
            privateKeyPem = privateKeyPem.copyOf(),
            certificatePem = certificatePem.copyOf()
        )
    }
}
