package com.flyfishxu.kadb

import sun.security.provider.X509Factory
import sun.security.x509.*
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.*

actual fun AdbKeyPair.Companion.generate(): AdbKeyPair {
    // TODO: TO BE TESTED SINCE DO NOT KNOW IF ANDROID CHANGED SEC-X509 BEHAVIOR
    // Generate a new key pair
    val keySize = 2048
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"))
    val generateKeyPair = keyPairGenerator.generateKeyPair()
    val publicKey = generateKeyPair.public
    val privateKey = generateKeyPair.private
    // Generate a new certificate
    val subject = "CN=WearOS Toolbox"
    val algorithmName = "SHA512withRSA"
    val expiryDate = System.currentTimeMillis() + 86400000
    val certificateExtensions = CertificateExtensions()
    certificateExtensions["SubjectKeyIdentifier"] =
        SubjectKeyIdentifierExtension(
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
    // Write files
    writePrivateKeyToFile(privateKey)
    writeCertificateToFile(certificate)
    return AdbKeyPair(privateKey, certificate.publicKey, certificate)
}

actual fun AdbKeyPair.Companion.writePrivateKeyToFile(privateKey: PrivateKey) {
    // TODO: TO BE TESTED SINCE DO NOT KNOW IF ANDROID CHANGED SEC-X509 BEHAVIOR
    val privateKeyFile = File(KadbInitializer.workDir, "adbKey")

    privateKeyFile.writer().use { out ->
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
        out.write("-----BEGIN PRIVATE KEY-----\n")
        out.write(base64.encodeToString(privateKey.encoded))
        out.write("\n-----END PRIVATE KEY-----")
    }
}

actual fun AdbKeyPair.Companion.writeCertificateToFile(certificate: Certificate) {
    val certFile = File(KadbInitializer.workDir, "cert.pem")
    val encoder = Base64.getEncoder()

    FileOutputStream(certFile).use { out ->
        out.write(X509Factory.BEGIN_CERT.toByteArray(StandardCharsets.US_ASCII))
        out.write("\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(encoder.encode(certificate.encoded))
        out.write("\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(X509Factory.END_CERT.toByteArray(StandardCharsets.US_ASCII))
    }
}

actual fun AdbKeyPair.Companion.getDeviceName(): String {
    val userName = System.getProperty("user.name")
    val hostName = System.getenv("HOSTNAME")
    return "$userName:$hostName@Kadb"
}