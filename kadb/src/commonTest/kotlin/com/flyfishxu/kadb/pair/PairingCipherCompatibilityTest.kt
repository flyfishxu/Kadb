package com.flyfishxu.kadb.pair

import com.flyfishxu.kadb.cert.JvmIdentityFixture
import kotlin.io.encoding.Base64
import kotlin.test.*
import okio.Buffer

/** JCA-generated vectors run against both the JVM BC and iOS BoringSSL backends. */
class PairingCipherCompatibilityTest {
    @Test fun hkdfAndAesGcmMatchJcaWireVectors() {
        val crypto = PairingCrypto("adb pair client\u0000".encodeToByteArray(), "adb pair server\u0000".encodeToByteArray())
        try {
            val key = crypto.deriveKey(ByteArray(64) { it.toByte() }, INFO)
            assertContentEquals(Base64.decode(JvmIdentityFixture.pairingKey), key)
            val plaintext = "pairing compatibility".encodeToByteArray()
            listOf(JvmIdentityFixture.cipher0, JvmIdentityFixture.cipher1).forEachIndexed { counter, encoded ->
                val nonce = Buffer().writeLongLe(counter.toLong()).writeIntLe(0).readByteArray()
                val expected = Base64.decode(encoded)
                assertContentEquals(expected, crypto.encryptDecrypt(true, key, nonce, plaintext))
                assertContentEquals(plaintext, crypto.encryptDecrypt(false, key, nonce, expected))
                expected[expected.lastIndex] = (expected.last().toInt() xor 1).toByte()
                assertNull(crypto.encryptDecrypt(false, key, nonce, expected), "Modified GCM tag must be rejected")
            }
            assertContentEquals(Base64.decode(JvmIdentityFixture.cipherEmpty),
                crypto.encryptDecrypt(true, key, ByteArray(12), byteArrayOf()))
            assertContentEquals(byteArrayOf(), crypto.encryptDecrypt(false, key, ByteArray(12),
                Base64.decode(JvmIdentityFixture.cipherEmpty)))
        } finally {
            crypto.destroy()
        }
    }
}
