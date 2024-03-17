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

import com.flyfishxu.kadb.pair.ByteArrayNoThrowOutputStream
import com.flyfishxu.kadb.pair.StringCompat
import org.jetbrains.annotations.VisibleForTesting
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.InvalidKeyException
import java.security.interfaces.RSAPublicKey
import java.util.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.ceil

internal object AndroidPubkey {
    /**
     * Size of an RSA modulus such as an encrypted block or a signature.
     */
    private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8

    /**
     * Size of an encoded RSA key.
     */
    private const val ANDROID_PUBKEY_ENCODED_SIZE = 3 * 4 + 2 * ANDROID_PUBKEY_MODULUS_SIZE

    /**
     * Size of the RSA modulus in words.
     */
    private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4

    @OptIn(ExperimentalUnsignedTypes::class)
    val SIGNATURE_PADDING = ubyteArrayOf(
        0x00u, 0x01u, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
        0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0x00u,
        0x30u, 0x21u, 0x30u, 0x09u, 0x06u, 0x05u, 0x2bu, 0x0eu, 0x03u, 0x02u, 0x1au, 0x05u, 0x00u,
        0x04u, 0x14u
    ).toByteArray()

    /**
     * The RSA signature padding as a byte array
     */
    private val RSA_SHA_PKCS1_SIGNATURE_PADDING = ByteArray(SIGNATURE_PADDING.size)

    init {
        for (i in RSA_SHA_PKCS1_SIGNATURE_PADDING.indices) RSA_SHA_PKCS1_SIGNATURE_PADDING[i] =
            SIGNATURE_PADDING[i]
    }

    /**
     * Converts a standard RSAPublicKey object to the special ADB format. Available since 4.2.2.
     *
     * @param publicKey RSAPublicKey object to convert
     * @param name      Name without null terminator
     * @return Byte array containing the converted RSAPublicKey object
     */
    @OptIn(ExperimentalEncodingApi::class)
    @JvmStatic
    @Throws(InvalidKeyException::class)
    // TODO: REWRITE THIS FUNCTION IN OKIO
    fun encodeWithName(publicKey: RSAPublicKey, name: String): ByteArray {
        val pkeySize = 4 * ceil(ANDROID_PUBKEY_ENCODED_SIZE / 3.0).toInt()
        ByteArrayNoThrowOutputStream(pkeySize + name.length + 2).use { bos ->
            bos.write(Base64.encode(encode(publicKey)).toByteArray())
            bos.write(getUserInfo(name))
            return bos.toByteArray()
        }
    }

    // Taken from get_user_info except that a custom name is used instead of host@user
    @VisibleForTesting
    fun getUserInfo(name: String): ByteArray {
        return StringCompat.getBytes(String.format(" %s\u0000", name), "UTF-8")
    }

    /**
     * Encodes the given key in the Android RSA public key binary format.
     *
     * @return Public RSA key in Android's custom binary format. The size of the key should be at least
     * [.ANDROID_PUBKEY_ENCODED_SIZE]
     */
    @Throws(InvalidKeyException::class)
    fun encode(publicKey: RSAPublicKey): ByteArray {
        if (publicKey.modulus.toByteArray().size < ANDROID_PUBKEY_MODULUS_SIZE) {
            throw InvalidKeyException("Invalid key length " + publicKey.modulus.toByteArray().size)
        }
        val keyStruct =
            ByteBuffer.allocate(ANDROID_PUBKEY_ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        // Store the modulus size.
        keyStruct.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS) // modulus_size_words

        // Compute and store n0inv = -1 / N[0] mod 2^32.
        val r32: BigInteger = BigInteger.ZERO.setBit(32) // r32 = 2^32
        var n0inv: BigInteger = publicKey.modulus.mod(r32) // n0inv = N[0] mod 2^32
        n0inv = n0inv.modInverse(r32) // n0inv = 1/n0inv mod 2^32
        n0inv = r32.subtract(n0inv) // n0inv = 2^32 - n0inv
        keyStruct.putInt(n0inv.toInt()) // n0inv

        // Store the modulus.
        keyStruct.put(
            Objects.requireNonNull(
                igbEndianToLittleEndianPadded(
                    ANDROID_PUBKEY_MODULUS_SIZE, publicKey.modulus
                )
            )
        )

        // Compute and store rr = (2^(rsa_size)) ^ 2 mod N.
        var rr: BigInteger =
            BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8) // rr = 2^(rsa_size)
        rr = rr.modPow(BigInteger.valueOf(2), publicKey.modulus) // rr = rr^2 mod N
        keyStruct.put(
            Objects.requireNonNull(
                igbEndianToLittleEndianPadded(
                    ANDROID_PUBKEY_MODULUS_SIZE, rr
                )
            )
        )

        // Store the exponent.
        keyStruct.putInt(publicKey.publicExponent.toInt()) // exponent
        return keyStruct.array()
    }

    private fun igbEndianToLittleEndianPadded(len: Int, `in`: BigInteger): ByteArray? {
        val out = ByteArray(len)
        val bytes = swapEndianness(`in`.toByteArray()) // Convert big endian -> little endian
        var numBytes = bytes.size
        if (len < numBytes) {
            if (!fitsInBytes(bytes, numBytes, len)) {
                return null
            }
            numBytes = len
        }
        System.arraycopy(bytes, 0, out, 0, numBytes)
        return out
    }

    private fun fitsInBytes(bytes: ByteArray, numBytes: Int, len: Int): Boolean {
        var mask: Byte = 0
        for (i in len until numBytes) {
            mask = (mask.toInt() or bytes[i].toInt()).toByte()
        }
        return mask.toInt() == 0
    }

    private fun swapEndianness(bytes: ByteArray): ByteArray {
        val len = bytes.size
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = bytes[len - i - 1]
        }
        return out
    }
}
