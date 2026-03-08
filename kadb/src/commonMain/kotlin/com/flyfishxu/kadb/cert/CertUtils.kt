package com.flyfishxu.kadb.cert

import okio.Buffer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val KEY_BEGIN = "-----BEGIN PRIVATE KEY-----"
private const val KEY_END = "-----END PRIVATE KEY-----"
private const val CERT_BEGIN = "-----BEGIN CERTIFICATE-----"
private const val CERT_END = "-----END CERTIFICATE-----"

internal data class ResolvedIdentity(
    val privateKeyPem: ByteArray,
    val keyPair: AdbKeyPair,
    val snapshot: KadbIdentitySnapshot
)

private val providers = listOf(
    null,
    BouncyCastleProvider(),
    "AndroidOpenSSL"
)

internal object CertUtils {
    fun loadKeyPair(): AdbKeyPair = KadbCert.currentKeyPair()

    fun loadKeySet(): HostKeySet = KadbCert.currentKeySet()

    fun generateNewIdentity(policy: KadbCertPolicy): ResolvedIdentity {
        val keyPair = generateKeyPair(policy.keySizeBits)
        val privateKeyPem = encodePrivateKeyPem(keyPair.private)
        return identityFromPrivateKey(privateKeyPem, policy)
    }

    fun identityFromPrivateKey(privateKeyPem: ByteArray, policy: KadbCertPolicy): ResolvedIdentity {
        val privateKey = parsePrivateKeyPem(privateKeyPem)
        val certificate = generateCertificateFromPrivateKey(privateKey, policy)
        return toResolvedIdentity(privateKeyPem, privateKey, certificate)
    }

    fun identityFromPrivateKeyAndCertificate(
        privateKeyPem: ByteArray,
        certificatePem: ByteArray
    ): ResolvedIdentity {
        val privateKey = parsePrivateKeyPem(privateKeyPem)
        val certificate = parseCertificatePem(certificatePem)
        val derivedPublicKey = derivePublicKey(privateKey)
        if (!certificate.publicKey.encoded.contentEquals(derivedPublicKey.encoded)) {
            throw KadbCertException.PolicyViolation("Certificate does not match private key")
        }

        return toResolvedIdentity(privateKeyPem, privateKey, certificate)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun parsePrivateKeyPem(privateKeyPem: ByteArray): PrivateKey {
        val normalized = privateKeyPem.decodeToString()
            .replace(KEY_BEGIN, "")
            .replace(KEY_END, "")
            .replace("\r", "")
            .replace("\n", "")
            .trim()

        val keyBytes = Base64.decode(normalized)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePrivate(keySpec)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun parseCertificatePem(certificatePem: ByteArray): X509Certificate {
        val normalized = certificatePem.decodeToString()
            .replace(CERT_BEGIN, "")
            .replace(CERT_END, "")
            .replace("\r", "")
            .replace("\n", "")
            .trim()
        val certBytes = Base64.decode(normalized)

        for (provider in providers) {
            try {
                val certificateFactory = when (provider) {
                    is Provider -> CertificateFactory.getInstance("X.509", provider)
                    is String -> CertificateFactory.getInstance("X.509", provider)
                    else -> CertificateFactory.getInstance("X.509")
                }
                return certificateFactory.generateCertificate(certBytes.inputStream()) as X509Certificate
            } catch (_: Throwable) {
            }
        }

        throw CertificateException("All certificate providers failed to parse certificate.")
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encodePrivateKeyPem(privateKey: PrivateKey): ByteArray {
        val base64 = Base64.encode(privateKey.encoded)
        val buffer = Buffer()
        buffer.use {
            it.writeUtf8(KEY_BEGIN)
            it.writeByte('\n'.code)
            it.writeUtf8(base64)
            it.writeByte('\n'.code)
            it.writeUtf8(KEY_END)
        }
        return buffer.readByteArray()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encodeCertificatePem(certificate: Certificate): ByteArray {
        val base64 = Base64.encode(certificate.encoded)
        val buffer = Buffer()
        buffer.use {
            it.writeUtf8(CERT_BEGIN)
            it.writeByte('\n'.code)
            it.writeUtf8(base64)
            it.writeByte('\n'.code)
            it.writeUtf8(CERT_END)
        }
        return buffer.readByteArray()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun fingerprintSha256(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return Base64.encode(digest)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun fingerprintSha256(certificate: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return Base64.encode(digest)
    }

    fun adbTlsFingerprintHex(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        val chars = CharArray(digest.size * 2)
        val hex = "0123456789ABCDEF"
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = hex[value ushr 4]
            chars[index * 2 + 1] = hex[value and 0x0f]
        }
        return String(chars).uppercase(Locale.US)
    }

    fun validateCertificate(certificate: X509Certificate) {
        certificate.checkValidity()
    }

    private fun toResolvedIdentity(
        privateKeyPem: ByteArray,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ): ResolvedIdentity {
        val resolvedKeyPair = AdbKeyPair(privateKey, certificate.publicKey, certificate)
        val snapshot = KadbIdentitySnapshot(
            privateKeyPem = privateKeyPem.copyOf(),
            certificatePem = encodeCertificatePem(certificate),
            fingerprintSha256 = fingerprintSha256(certificate),
            notAfterEpochMillis = certificate.notAfter.time
        )

        return ResolvedIdentity(
            privateKeyPem = privateKeyPem.copyOf(),
            keyPair = resolvedKeyPair,
            snapshot = snapshot
        )
    }

    private fun generateKeyPair(keySizeBits: Int): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(keySizeBits, SecureRandom.getInstance("SHA1PRNG"))
        return generator.generateKeyPair()
    }

    private fun generateCertificateFromPrivateKey(
        privateKey: PrivateKey,
        policy: KadbCertPolicy
    ): X509Certificate {
        val publicKey = derivePublicKey(privateKey)
        val now = System.currentTimeMillis()
        val notBefore = Time(Date(now))
        val notAfter = Time(Date(now + TimeUnit.DAYS.toMillis(policy.certValidityDays.toLong())))
        val subject = buildSubjectName(policy.subject)
        val x500 = X500Name(subject)
        val serialNumber = BigInteger.ONE

        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            x500,
            serialNumber,
            notBefore,
            notAfter,
            x500,
            publicKey
        )

        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature)
        )
        val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            BcX509ExtensionUtils().createSubjectKeyIdentifier(subjectPublicKeyInfo)
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val holder = builder.build(signer)

        for (provider in providers) {
            try {
                return when (provider) {
                    is Provider -> JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
                    is String -> JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
                    else -> JcaX509CertificateConverter().getCertificate(holder)
                }
            } catch (_: Throwable) {
            }
        }

        throw CertificateException("All certificate providers failed to generate a certificate.")
    }

    private fun derivePublicKey(privateKey: PrivateKey): PublicKey {
        val rsaPrivate = privateKey as? RSAPrivateCrtKey
            ?: throw KadbCertException.PolicyViolation("Only RSA private keys with CRT parameters are supported")
        val spec = RSAPublicKeySpec(rsaPrivate.modulus, rsaPrivate.publicExponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    private fun buildSubjectName(subject: KadbCertPolicy.Subject): String {
        val entries = mutableListOf<String>()
        appendSubjectEntry(entries, "C", subject.c)
        appendSubjectEntry(entries, "O", subject.o)
        appendSubjectEntry(entries, "CN", subject.cn)
        appendSubjectEntry(entries, "OU", subject.ou)
        appendSubjectEntry(entries, "L", subject.l)
        appendSubjectEntry(entries, "ST", subject.st)
        if (entries.isEmpty()) {
            entries += "C=US"
            entries += "O=Android"
            entries += "CN=Adb"
        }
        return entries.joinToString(", ")
    }

    private fun appendSubjectEntry(entries: MutableList<String>, key: String, value: String) {
        if (value.isNotBlank()) {
            entries += "$key=$value"
        }
    }
}
