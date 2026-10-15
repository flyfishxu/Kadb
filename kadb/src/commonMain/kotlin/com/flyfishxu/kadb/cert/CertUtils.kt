package com.flyfishxu.kadb.cert

internal data class ResolvedIdentity(
    val privateKeyPem: ByteArray,
    val keyPair: AdbKeyPair,
    val snapshot: KadbIdentitySnapshot
)

/** Identity encoding and crypto are supplied by the platform backend. */
internal expect object CertUtils {
    fun loadKeyPair(): AdbKeyPair
    fun loadKeySet(): HostKeySet
    fun generateNewIdentity(policy: KadbCertPolicy): ResolvedIdentity
    fun identityFromPrivateKey(privateKeyPem: ByteArray, policy: KadbCertPolicy): ResolvedIdentity
    fun identityFromPrivateKeyAndCertificate(privateKeyPem: ByteArray, certificatePem: ByteArray): ResolvedIdentity
    fun validatePrivateKeyPem(privateKeyPem: ByteArray)
}
