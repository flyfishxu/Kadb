package com.flyfishxu.kadb

import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Base64
import java.util.Date
import java.util.Random

actual fun AdbKeyPair.Companion.generate(): AdbKeyPair {
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
    val privateKeyFile = File(KadbInitializer.workDir, "adbKey")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        privateKeyFile.writer().use { out ->
            val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            out.write("-----BEGIN PRIVATE KEY-----\n")
            out.write(base64.encodeToString(privateKey.encoded))
            out.write("\n-----END PRIVATE KEY-----")
        }
    } else {
        privateKeyFile.writer().use { out ->
            val base64 = android.util.Base64.encodeToString(
                privateKey.encoded, android.util.Base64.DEFAULT
            )
            out.write("-----BEGIN PRIVATE KEY-----\n")
            out.write(base64)
            out.write("\n-----END PRIVATE KEY-----")
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

actual fun AdbKeyPair.Companion.getDeviceName(): String {
    return "${Build.MODEL.replace(" ", "")}@Kadb"
}