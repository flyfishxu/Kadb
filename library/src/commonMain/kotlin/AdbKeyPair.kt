/*
 * Copyright (c) 2024 Flyfish-Xu
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.flyfishxu.kadb

import com.flyfishxu.kadb.AndroidPubkey.SIGNATURE_PADDING
import com.flyfishxu.kadb.KadbInitializer.workDir
import java.io.File
import java.io.FileInputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import javax.crypto.Cipher

class AdbKeyPair(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    val certificate: Certificate
) {
    internal fun signPayload(message: AdbMessage): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(message.payload, 0, message.payloadLength)
    }

    companion object {

        private fun readCertificateFromFile(): Certificate? {
            val certFile = File(workDir, "cert.pem")
            if (!certFile.exists()) return null
            FileInputStream(certFile).use { cert ->
                return CertificateFactory.getInstance("X.509").generateCertificate(cert)
            }
        }

        private fun readPrivateKeyFromFile(): PrivateKey? {
            val privateKeyFile = File(workDir, "adbKey")
            return try {
                PKCS8.parse(privateKeyFile.readBytes())
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun read(): AdbKeyPair {
            val privateKey = readPrivateKeyFromFile()
            val certificate = readCertificateFromFile()
            return if (privateKey == null || certificate == null) generate()
            else AdbKeyPair(privateKey, certificate.publicKey, certificate)
        }

    }
}

expect fun AdbKeyPair.Companion.generate(): AdbKeyPair

expect fun AdbKeyPair.Companion.writePrivateKeyToFile(privateKey: PrivateKey)

expect fun AdbKeyPair.Companion.writeCertificateToFile(certificate: Certificate)

expect fun AdbKeyPair.Companion.getDeviceName(): String