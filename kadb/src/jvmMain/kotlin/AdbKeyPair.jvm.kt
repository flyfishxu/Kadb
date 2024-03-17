package com.flyfishxu.kadb

import java.io.File
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Base64

actual fun AdbKeyPair.Companion.writePrivateKeyToFile(privateKey: PrivateKey) {
    val privateKeyFile = File(KadbInitializer.workDir, "adbKey")

    privateKeyFile.writer().use { out ->
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
        out.write("-----BEGIN PRIVATE KEY-----\n")
        out.write(base64.encodeToString(privateKey.encoded))
        out.write("\n-----END PRIVATE KEY-----")
    }
}

actual fun AdbKeyPair.Companion.writeCertificateToFile(certificate: Certificate) {
}

actual fun AdbKeyPair.Companion.getDeviceName(): String {
    TODO("Not yet implemented")
}

actual fun AdbKeyPair.Companion.generate(
    keySize: Int,
    subject: String
): AdbKeyPair {
    TODO("Not yet implemented")
}