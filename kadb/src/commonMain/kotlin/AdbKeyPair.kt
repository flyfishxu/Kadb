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
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.*
import javax.crypto.Cipher
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val KEY_BEGIN = "-----BEGIN PRIVATE KEY-----"
private const val KEY_END = "-----END PRIVATE KEY-----"

private const val CERT_BEGIN = "-----BEGIN CERTIFICATE-----"
private const val CERT_END = "-----END CERTIFICATE-----"


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
            // TODO: DO NOT HARD CODE CERT FILE
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

    // TODO: USE "-----BEGIN PRIVATE KEY-----\n" or add a WriteByte as now on? Which prettier or performance?

    privateKeyFile.sink().buffer().use { sink ->
        sink.writeUtf8(KEY_BEGIN)
        sink.writeByte('\n'.code)
        sink.writeUtf8(Base64.encode(privateKey.encoded))
        sink.writeByte('\n'.code)
        sink.writeUtf8(KEY_END)
    }
}

@OptIn(ExperimentalEncodingApi::class)
fun AdbKeyPair.Companion.writeCertificateToFile(certificate: Certificate) {
    val certFile = File(workDir, "cert.pem")

    certFile.sink().buffer().use { sink ->
        sink.writeUtf8(CERT_BEGIN)
        sink.writeByte('\n'.code)
        sink.writeUtf8(Base64.encode(certificate.encoded))
        sink.writeByte('\n'.code)
        sink.writeUtf8(CERT_END)
    }
}

fun AdbKeyPair.Companion.generate(
    keySize: Int = 2048,
    cn: String = "Kadb",
    ou: String = "Kadb",
    o: String = "Kadb",
    l: String = "Kadb",
    st: String = "Kadb",
    c: String = "Kadb",
    notAfter: Time = Time(Date(System.currentTimeMillis() + 864000000)), // 10 days
    serialNumber: BigInteger = BigInteger(64, SecureRandom())
): AdbKeyPair {
    Security.addProvider(BouncyCastleProvider())

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
    val certificate =
        JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(certificateHolder)

    // Write to files
    writePrivateKeyToFile(privateKey)
    writeCertificateToFile(certificate)

    return AdbKeyPair(privateKey, publicKey, certificate)
}


expect fun AdbKeyPair.Companion.getDeviceName(): String
