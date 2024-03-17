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
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileInputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import javax.crypto.Cipher
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val KEY_BEGIN = "-----BEGIN PRIVATE KEY-----\n"
private const val KEY_END = "-----END PRIVATE KEY-----"

class AdbKeyPair(
    val privateKey: PrivateKey, val publicKey: PublicKey, val certificate: Certificate
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
            if (!privateKeyFile.exists()) return null
            return PKCS8.parse(privateKeyFile.readBytes())
        }

        internal fun read(): AdbKeyPair {
            val privateKey = readPrivateKeyFromFile()
            val certificate = readCertificateFromFile()
            return if (privateKey == null || certificate == null) generate()
            else AdbKeyPair(privateKey, certificate.publicKey, certificate)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
fun AdbKeyPair.Companion.writePrivateKeyToFile(privateKey: PrivateKey) {
    val privateKeyFile = File(workDir, "adbKey")

    privateKeyFile.sink().buffer().use { sink ->
        sink.writeUtf8(KEY_BEGIN)
        sink.writeUtf8(Base64.encode(privateKey.encoded))
        sink.writeUtf8(KEY_END)
    }
}

expect fun AdbKeyPair.Companion.writeCertificateToFile(certificate: Certificate)

expect fun AdbKeyPair.Companion.getDeviceName(): String
expect fun AdbKeyPair.Companion.generate(
    keySize: Int = 2048, subject: String = "CN=Kadb"
): AdbKeyPair