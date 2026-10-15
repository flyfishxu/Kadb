@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flyfishxu.kadb.pair

import com.flyfishxu.kadb.cert.withNativeBytes
import com.flyfishxu.kadb.nativebridge.*
import kotlin.test.*
import okio.Buffer

class PairingSpakeCompatibilityTest {
    @Test fun pairsAndUsesIndependentNonceCounters() = exchange(wrongPassword = false)
    @Test fun wrongPasswordFailsAtAuthenticatedDecryption() = exchange(wrongPassword = true)

    private fun exchange(wrongPassword: Boolean) {
        val clientName = "adb pair client\u0000".encodeToByteArray()
        val serverName = "adb pair server\u0000".encodeToByteArray()
        val password = "123456-tls-exported-session-binding".encodeToByteArray()
        val alice = assertNotNull(PairingAuthCtx.createAlice(password))
        val bob = assertNotNull(serverName.withNativeBytes { me, myLength ->
            clientName.withNativeBytes { peer, peerLength -> kadb_spake_new(0, me, myLength, peer, peerLength) }
        })
        val cipher = PairingCrypto(clientName, serverName)
        try {
            val serverPassword = if (wrongPassword) "654321-tls-exported-session-binding".encodeToByteArray() else password
            val serverMessage = ByteArray(32)
            assertEquals(1, serverPassword.withNativeBytes { bytes, length ->
                serverMessage.withNativeBytes { output, _ -> kadb_spake_generate(bob, bytes, length, output) }
            })
            val material = ByteArray(64)
            assertEquals(1, alice.msg.withNativeBytes { bytes, length ->
                material.withNativeBytes { output, _ -> kadb_spake_process(bob, bytes, length, output) }
            })
            val key = cipher.deriveKey(material, INFO)
            material.fill(0)
            assertTrue(alice.initCipher(serverMessage))
            if (wrongPassword) {
                assertNull(cipher.encryptDecrypt(false, key, ByteArray(12), assertNotNull(alice.encrypt(byteArrayOf(1)))))
            } else {
                repeat(2) { counter ->
                    val nonce = Buffer().writeLongLe(counter.toLong()).writeIntLe(0).readByteArray()
                    val plaintext = "peer-info-$counter".encodeToByteArray()
                    val serverEncrypted = assertNotNull(cipher.encryptDecrypt(true, key, nonce, plaintext))
                    assertContentEquals(serverEncrypted, alice.encrypt(plaintext))
                    assertContentEquals(plaintext, alice.decrypt(serverEncrypted))
                }
                val replay = assertNotNull(cipher.encryptDecrypt(true, key, ByteArray(12), byteArrayOf(1)))
                assertNull(alice.decrypt(replay))
            }
            key.fill(0)
            alice.destroy()
            assertTrue(alice.isDestroyed())
            assertNull(alice.encrypt(byteArrayOf(1)))
            assertNull(alice.decrypt(byteArrayOf(1)))
        } finally {
            alice.destroy()
            cipher.destroy()
            kadb_spake_free(bob)
            password.fill(0)
        }
    }
}
