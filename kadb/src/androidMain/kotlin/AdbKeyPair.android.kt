package com.flyfishxu.kadb

import android.os.Build
import android.sun.security.provider.X509Factory
import android.sun.security.x509.*
import okio.buffer
import okio.sink
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

// TODO: DO NOT HARD CODE THE DEVICE NAME
actual fun AdbKeyPair.Companion.getDeviceName(): String {
    return "${Build.MODEL.replace(" ", "")}@Kadb"
}