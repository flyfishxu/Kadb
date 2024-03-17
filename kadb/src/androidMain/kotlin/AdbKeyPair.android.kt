package com.flyfishxu.kadb

import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.*
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.*

private const val KEY_BEGIN = "-----BEGIN PRIVATE KEY-----\n"
private const val KEY_END = "-----END PRIVATE KEY-----"

actual fun AdbKeyPair.Companion.generate(
    keySize: Int, subject: String

): AdbKeyPair {
    // For generating a new key pair
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"))
    val generateKeyPair = keyPairGenerator.generateKeyPair()
    val publicKey = generateKeyPair.public
    val privateKey = generateKeyPair.private
    // Generate a new certificate
    val algorithmName = "SHA512withRSA"
    val expiryDate = System.currentTimeMillis() + 86400000
    val certificateExtensions = CertificateExtensions()
    certificateExtensions["SubjectKeyIdentifier"] = SubjectKeyIdentifierExtension(
        KeyIdentifier(publicKey).identifier
    )
    val x500Name = X500Name(subject)
    val notBefore = Date()
    val notAfter = Date(expiryDate)
    certificateExtensions["PrivateKeyUsage"] = PrivateKeyUsageExtension(notBefore, notAfter)
    val certificateValidity = CertificateValidity(notBefore, notAfter)
    val x509CertInfo = X509CertInfo()
    x509CertInfo["version"] = CertificateVersion(2)
    x509CertInfo["serialNumber"] = CertificateSerialNumber(
        Random().nextInt() and Int.MAX_VALUE
    )
    x509CertInfo["algorithmID"] = CertificateAlgorithmId(
        AlgorithmId.get(algorithmName)
    )
    x509CertInfo["subject"] = CertificateSubjectName(x500Name)
    x509CertInfo["key"] = CertificateX509Key(publicKey)
    x509CertInfo["validity"] = certificateValidity
    x509CertInfo["issuer"] = CertificateIssuerName(x500Name)
    x509CertInfo["extensions"] = certificateExtensions
    val certificate = X509CertImpl(x509CertInfo)
    certificate.sign(privateKey, algorithmName)
    // Write to files
    writePrivateKeyToFile(privateKey)
    writeCertificateToFile(certificate)
    return AdbKeyPair(privateKey, certificate.publicKey, certificate)
}

actual fun AdbKeyPair.Companion.writePrivateKeyToFile(privateKey: PrivateKey) {
    val privateKeyFile = File(KadbInitializer.workDir, "adbKey")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        privateKeyFile.writer().use { out ->
            val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            out.write(KEY_BEGIN)
            out.write(base64.encodeToString(privateKey.encoded))
            out.write(KEY_END)
        }
    } else {
        privateKeyFile.writer().use { out ->
            val base64 = android.util.Base64.encodeToString(
                privateKey.encoded, android.util.Base64.DEFAULT
            )
            out.write(KEY_BEGIN)
            out.write(base64)
            out.write(KEY_END)
        }
    }
}

actual fun AdbKeyPair.Companion.writeCertificateToFile(certificate: Certificate) {
    val certFile = File(KadbInitializer.workDir, "cert.pem")
    val encoder = BASE64Encoder()

    FileOutputStream(certFile).use { os ->
        os.write(X509Factory.BEGIN_CERT.toByteArray(StandardCharsets.UTF_8))
        os.write('\n'.code)
        encoder.encode(certificate.encoded, os)
        os.write('\n'.code)
        os.write(X509Factory.END_CERT.toByteArray(StandardCharsets.UTF_8))
    }
}

// TODO: DO NOT HARD CODE THE DEVICE NAME
actual fun AdbKeyPair.Companion.getDeviceName(): String {
    return "${Build.MODEL.replace(" ", "")}@Kadb"
}