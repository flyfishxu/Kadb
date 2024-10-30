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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.util.*
import javax.crypto.Cipher
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val KEY_BEGIN = "-----BEGIN PRIVATE KEY-----"
private const val KEY_END = "-----END PRIVATE KEY-----"

private const val CERT_BEGIN = "-----BEGIN CERTIFICATE-----"
private const val CERT_END = "-----END CERTIFICATE-----"

private const val KEY_LENGTH_BITS = 2048
private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4

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
fun AdbKeyPair.Companion.adbPublicKey(keyPair: AdbKeyPair): ByteArray {
    val pubkey = keyPair.publicKey as RSAPublicKey
    val bytes = AdbKeyPair.Companion.convertRsaPublicKeyToAdbFormat(pubkey)
    return Base64.encodeToByteArray(bytes) + " ${AdbKeyPair.getDeviceName()}}".encodeToByteArray()
}

@OptIn(ExperimentalEncodingApi::class)
fun AdbKeyPair.Companion.writePrivateKeyToFile(privateKey: PrivateKey) {
    val privateKeyFile = File(workDir, "adbKey")

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
    notAfter: Time = Time(Date(System.currentTimeMillis() + 10368000000)), // 120 days
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

// https://github.com/cgutman/AdbLib/blob/d6937951eb98557c76ee2081e383d50886ce109a/src/com/cgutman/adblib/AdbCrypto.java#L83-L137
@Suppress("JoinDeclarationAndAssignment")
fun AdbKeyPair.Companion.convertRsaPublicKeyToAdbFormat(pubkey: RSAPublicKey): ByteArray {/*
     * ADB literally just saves the RSAPublicKey struct to a file.
     *
     * typedef struct RSAPublicKey {
     * int len; // Length of n[] in number of uint32_t
     * uint32_t n0inv;  // -1 / n[0] mod 2^32
     * uint32_t n[RSANUMWORDS]; // modulus as little endian array
     * uint32_t rr[RSANUMWORDS]; // R^2 as little endian array
     * int exponent; // 3 or 65537
     * } RSAPublicKey;
     */

    /* ------ This part is a Java-ified version of RSA_to_RSAPublicKey from adb_host_auth.c ------ */
    val r32: BigInteger
    val r: BigInteger
    var rr: BigInteger
    var rem: BigInteger
    var n: BigInteger
    val n0inv: BigInteger
    r32 = BigInteger.ZERO.setBit(32)
    n = pubkey.modulus
    r = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32)
    rr = r.modPow(BigInteger.valueOf(2), n)
    rem = n.remainder(r32)
    n0inv = rem.modInverse(r32)
    val myN = IntArray(KEY_LENGTH_WORDS)
    val myRr = IntArray(KEY_LENGTH_WORDS)
    var res: Array<BigInteger>
    for (i in 0 until KEY_LENGTH_WORDS) {
        res = rr.divideAndRemainder(r32)
        rr = res[0]
        rem = res[1]
        myRr[i] = rem.toInt()
        res = n.divideAndRemainder(r32)
        n = res[0]
        rem = res[1]
        myN[i] = rem.toInt()
    }

    /* ------------------------------------------------------------------------------------------- */
    val bbuf: ByteBuffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
    bbuf.putInt(KEY_LENGTH_WORDS)
    bbuf.putInt(n0inv.negate().toInt())
    for (i in myN) bbuf.putInt(i)
    for (i in myRr) bbuf.putInt(i)
    bbuf.putInt(pubkey.publicExponent.toInt())
    return bbuf.array()
}

expect fun AdbKeyPair.Companion.getDeviceName(): String
