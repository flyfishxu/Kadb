@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flyfishxu.kadb.cert

import cnames.structs.kadb_identity
import com.flyfishxu.kadb.nativebridge.*
import kotlinx.cinterop.CPointer
import okio.ByteString.Companion.toByteString

internal actual object CertUtils {
    actual fun loadKeyPair(): AdbKeyPair = KadbCert.currentKeyPair()
    actual fun loadKeySet(): HostKeySet = KadbCert.currentKeySet()

    actual fun validatePrivateKeyPem(privateKeyPem: ByteArray) {
        withNativeIdentity(decodeIdentityPem(privateKeyPem, "PRIVATE KEY")) { }
    }

    actual fun generateNewIdentity(policy: KadbCertPolicy): ResolvedIdentity {
        val identity = kadb_identity_generate(policy.keySizeBits)
            ?: throw KadbCertException.PolicyViolation("Failed to generate RSA identity")
        return try {
            val privateDer = readNativeBlob { bytes, length -> kadb_identity_private_der(identity, bytes, length) }
            createCertificate(identity, policy)
            resolved(identity, encodeIdentityPem(privateDer, "PRIVATE KEY"), privateDer)
        } finally {
            kadb_identity_free(identity)
        }
    }

    actual fun identityFromPrivateKey(privateKeyPem: ByteArray, policy: KadbCertPolicy): ResolvedIdentity {
        val privateDer = decodeIdentityPem(privateKeyPem, "PRIVATE KEY")
        return withNativeIdentity(privateDer) { identity ->
            createCertificate(identity, policy)
            resolved(identity, privateKeyPem, privateDer)
        }
    }

    actual fun identityFromPrivateKeyAndCertificate(privateKeyPem: ByteArray, certificatePem: ByteArray): ResolvedIdentity {
        val privateDer = decodeIdentityPem(privateKeyPem, "PRIVATE KEY")
        val certificateDer = decodeIdentityPem(certificatePem, "CERTIFICATE")
        return withNativeIdentity(privateDer) { identity ->
            if (certificateDer.withNativeBytes { bytes, length ->
                kadb_identity_set_certificate(identity, bytes, length)
            } != 1) throw KadbCertException.PolicyViolation("Invalid certificate or certificate does not match private key")
            resolved(identity, privateKeyPem, privateDer)
        }
    }

    private fun createCertificate(identity: CPointer<kadb_identity>, policy: KadbCertPolicy) {
        val requested = policy.subject
        val subject = if (listOf(requested.c, requested.o, requested.cn, requested.ou, requested.l, requested.st)
                .all(String::isBlank)) KadbCertPolicy.Subject() else requested
        fun String.entry() = if (isBlank()) "" else this
        check(kadb_identity_create_certificate(identity, policy.certValidityDays,
            subject.c.entry(), subject.o.entry(), subject.cn.entry(), subject.ou.entry(),
            subject.l.entry(), subject.st.entry()) == 1) { "Failed to generate X.509 certificate" }
    }

    private fun resolved(identity: CPointer<kadb_identity>, privatePem: ByteArray, privateDer: ByteArray): ResolvedIdentity {
        val certificateDer = readNativeBlob { bytes, length -> kadb_identity_certificate_der(identity, bytes, length) }
        val publicDer = readNativeBlob { bytes, length -> kadb_identity_public_der(identity, bytes, length) }
        val certificatePem = encodeIdentityPem(certificateDer, "CERTIFICATE")
        val pair = AdbKeyPair(privatePem.copyOf(), certificatePem.copyOf(), privateDer.copyOf(), certificateDer, publicDer)
        val snapshot = KadbIdentitySnapshot(
            privateKeyPem = privatePem.copyOf(),
            certificatePem = certificatePem,
            fingerprintSha256 = certificateDer.toByteString().sha256().base64(),
            notAfterEpochMillis = kadb_identity_not_after(identity)
        )
        return ResolvedIdentity(privatePem.copyOf(), pair, snapshot)
    }
}
