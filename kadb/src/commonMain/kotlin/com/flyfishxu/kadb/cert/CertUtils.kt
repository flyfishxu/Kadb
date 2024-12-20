package com.flyfishxu.kadb.cert

import com.flyfishxu.kadb.debug.log
import okio.Buffer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.*
import java.security.cert.*
import java.security.cert.Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import kotlin.Throws
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val KEY_BEGIN = "-----BEGIN PRIVATE KEY-----"
private const val KEY_END = "-----END PRIVATE KEY-----"

private const val CERT_BEGIN = "-----BEGIN CERTIFICATE-----"
private const val CERT_END = "-----END CERTIFICATE-----"

private val providers = listOf(
    null,
    BouncyCastleProvider(),
    "AndroidOpenSSL"
)

private fun readCertificate(): Certificate? {
    val cert = KadbCert.cert
    if (cert.isEmpty()) return null

    for (provider in providers) {
        try {
            val certFactory = when (provider) {
                is String -> CertificateFactory.getInstance("X.509", provider)
                is Provider -> CertificateFactory.getInstance("X.509", provider)
                else -> CertificateFactory.getInstance("X.509")
            }
            return certFactory.generateCertificate(cert.inputStream())
        } catch (_: CertificateException) {
            log { "Failed to generate certificate with provider: $provider" }
        }
    }

    throw CertificateException("All certificate providers failed to generate a certificate.")
}

private fun readPrivateKey(): PrivateKey? {
    val privateKey = KadbCert.key
    if (privateKey.isEmpty()) return null
    return parsePCKS8(privateKey)
}

@OptIn(ExperimentalEncodingApi::class)
private fun writePrivateKey(privateKey: PrivateKey): ByteArray {
    val buffer = Buffer()
    buffer.use { sink ->
        sink.writeUtf8(KEY_BEGIN)
        sink.writeByte('\n'.code)
        sink.writeUtf8(Base64.encode(privateKey.encoded))
        sink.writeByte('\n'.code)
        sink.writeUtf8(KEY_END)
    }
    return buffer.readByteArray()
}

@OptIn(ExperimentalEncodingApi::class)
private fun writeCertificate(certificate: Certificate): ByteArray {
    val buffer = Buffer()
    buffer.use { sink ->
        sink.writeUtf8(CERT_BEGIN)
        sink.writeByte('\n'.code)
        sink.writeUtf8(Base64.encode(certificate.encoded))
        sink.writeByte('\n'.code)
        sink.writeUtf8(CERT_END)
    }
    return buffer.readByteArray()
}

@OptIn(ExperimentalEncodingApi::class)
private fun parsePCKS8(bytes: ByteArray): PrivateKey {
    val string = String(bytes).replace(KEY_BEGIN, "").replace(KEY_END, "").replace("\n", "")
    val encoded = Base64.Default.decode(string)
    val keyFactory = KeyFactory.getInstance("RSA")
    val keySpec = PKCS8EncodedKeySpec(encoded)
    return keyFactory.generatePrivate(keySpec)
}

internal object CertUtils {
    fun loadKeyPair(): AdbKeyPair {
        val privateKey = readPrivateKey()
        val certificate = readCertificate()
        // validateCertificate() -> Is that redundant?
        return if (privateKey == null || certificate == null) generate()
        else AdbKeyPair(privateKey, certificate.publicKey, certificate)
    }

    @Throws(CertificateExpiredException::class, CertificateNotYetValidException::class)
    fun validateCertificate() {
        val x509Certificate = readCertificate() as X509Certificate
        x509Certificate.checkValidity()
    }

    fun generate(
        keySize: Int = 2048,
        cn: String = "Kadb",
        ou: String = "Kadb",
        o: String = "Kadb",
        l: String = "Kadb",
        st: String = "Kadb",
        c: String = "Kadb",
        notAfter: Time = Time(Date(System.currentTimeMillis() + 10368000000)), // 120 days
        serialNumber: BigInteger = BigInteger(64, SecureRandom())
    ): AdbKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"))
        val generateKeyPair = keyPairGenerator.generateKeyPair()
        val publicKey = generateKeyPair.public
        val privateKey = generateKeyPair.private

        val notBefore = Time(Date(System.currentTimeMillis()))
        val subject = "CN=$cn, OU=$ou, O=$o, L=$l, ST=$st, C=$c"

        val x500Name = X500Name(subject)
        val x509v3CertificateBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            x500Name, serialNumber, notBefore, notAfter, x500Name, publicKey
        )

        val contentSigner = JcaContentSignerBuilder("SHA512withRSA").build(privateKey)
        val certificateHolder = x509v3CertificateBuilder.build(contentSigner)

        var certificate: X509Certificate? = null
        for (provider in providers) {
            try {
                certificate = when (provider) {
                    is Provider -> JcaX509CertificateConverter().setProvider(provider).getCertificate(certificateHolder)
                    is String -> JcaX509CertificateConverter().setProvider(provider).getCertificate(certificateHolder)
                    else -> JcaX509CertificateConverter().getCertificate(certificateHolder)
                }
            } catch (e: CertificateException) {
                e.printStackTrace()
            }
        }
        if (certificate == null) throw CertificateException("All certificate providers failed to generate a certificate.")

        KadbCert.key = writePrivateKey(privateKey)
        KadbCert.cert = writeCertificate(certificate)

        return AdbKeyPair(privateKey, publicKey, certificate)
    }
}