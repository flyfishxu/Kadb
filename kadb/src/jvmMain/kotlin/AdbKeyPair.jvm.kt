package com.flyfishxu.kadb

import java.security.cert.Certificate

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