package com.flyfishxu.kadb.pair

import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import com.flyfishxu.kadb.cert.CertUtils
import com.flyfishxu.kadb.cert.KadbCertPolicy
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import org.conscrypt.Conscrypt
import kotlin.test.*

/** A loopback peer checks the wire format using JCA rather than Kadb's BC cipher. */
class PairingCompatibilityTest {
    @Test fun pairsOverTlsWithSessionBindingAndBigEndianFrames() = pairWithPeer(wrongCode = false)

    @Test fun rejectsWrongPairingCode() = pairWithPeer(wrongCode = true)

    private fun pairWithPeer(wrongCode: Boolean) {
        val identity = CertUtils.generateNewIdentity(KadbCertPolicy())
        // Kadb's key manager is client-only. Give the test peer a real server identity.
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("peer", identity.keyPair.privateKey, charArrayOf(), arrayOf(identity.keyPair.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, charArrayOf())
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(keyManagers.keyManagers, trustManagers.trustManagers, null)
        }
        val executor = Executors.newSingleThreadExecutor()
        val code = "123456".encodeToByteArray()
        val name = "kadb-test"
        val expectedPublicKey = identity.keyPair.encodeAdbPublicKey(name)
        val server = context.serverSocketFactory.createServerSocket(
            0, 1, InetAddress.getLoopbackAddress()
        ) as SSLServerSocket
        server.soTimeout = 5000
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.needClientAuth = true
        try {
            val peer = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    assertEquals("TLSv1.3", socket.session.protocol)
                    assertTrue(socket.session.peerCertificates.isNotEmpty())
                    val binding = Conscrypt.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)
                    val password = (if (wrongCode) "654321".encodeToByteArray() else code.copyOf()) + binding
                    val bob = serverSpake()
                    try {
                        val message = bob.generateMessage(password)
                        val input = DataInputStream(socket.inputStream)
                        val output = DataOutputStream(socket.outputStream)
                        val clientMessage = input.frame(0)
                        output.frame(0, message)
                        val key = deriveKey(bob.processMessage(clientMessage))
                        val encrypted = input.frame(1)
                        if (wrongCode) {
                            assertFailsWith<AEADBadTagException> { decrypt(key, 0, encrypted) }
                        } else {
                            val info = decrypt(key, 0, encrypted)
                            assertEquals(8192, info.size)
                            assertEquals(0, info[0].toInt())
                            assertContentEquals(expectedPublicKey, info.copyOfRange(1, 1 + expectedPublicKey.size))
                            assertTrue(info.drop(1 + expectedPublicKey.size).all { it == 0.toByte() })
                            val response = ByteArray(8192)
                            response[0] = 1 // ADB_DEVICE_GUID
                            "adb-test-guid\u0000".encodeToByteArray().copyInto(response, 1)
                            output.frame(1, encrypt(key, 0, response))
                        }
                    } finally {
                        bob.destroy()
                    }
                }
            }
            PairingConnectionCtx(server.inetAddress.hostAddress, server.localPort, code, identity.keyPair, name).use {
                if (wrongCode) assertFailsWith<IOException> { it.start() } else it.start()
            }
            peer.get(10, TimeUnit.SECONDS)
            assertTrue(code.all { it == 0.toByte() }, "Closing pairing must erase the supplied password")
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun cipherUsesIndependentLittleEndianCountersAndRejectsReplay() {
        val password = "test-session-bound-password".encodeToByteArray()
        val alice = assertNotNull(PairingAuthCtx.createAlice(password))
        val bob = serverSpake()
        try {
            val message = bob.generateMessage(password)
            val key = deriveKey(bob.processMessage(alice.msg))
            assertTrue(alice.initCipher(message))
            repeat(2) { counter ->
                val payload = "message-$counter".encodeToByteArray()
                assertContentEquals(encrypt(key, counter.toLong(), payload), alice.encrypt(payload))
                assertContentEquals(payload, alice.decrypt(encrypt(key, counter.toLong(), payload)))
            }
            assertNull(alice.decrypt(encrypt(key, 0, byteArrayOf(1))), "Old nonce must not authenticate")
            alice.destroy()
            assertTrue(alice.isDestroyed())
            assertNull(alice.encrypt(byteArrayOf(1)))
            assertNull(alice.decrypt(byteArrayOf(1)))
        } finally {
            if (!alice.isDestroyed()) alice.destroy()
            bob.destroy()
        }
    }

    private fun serverSpake() = Spake2Context(
        Spake2Role.Bob, "adb pair server\u0000".encodeToByteArray(), "adb pair client\u0000".encodeToByteArray()
    )

    private fun deriveKey(material: ByteArray): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = hmac.doFinal(material)
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        return hmac.doFinal("adb pairing_auth aes-128-gcm key".encodeToByteArray() + byteArrayOf(1)).copyOf(16)
    }

    private fun encrypt(key: ByteArray, counter: Long, bytes: ByteArray) = crypt(Cipher.ENCRYPT_MODE, key, counter, bytes)
    private fun decrypt(key: ByteArray, counter: Long, bytes: ByteArray) = crypt(Cipher.DECRYPT_MODE, key, counter, bytes)

    private fun crypt(mode: Int, key: ByteArray, counter: Long, bytes: ByteArray): ByteArray {
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(bytes)
        }
    }

    private fun DataInputStream.frame(type: Int): ByteArray {
        assertEquals(1, readUnsignedByte())
        assertEquals(type, readUnsignedByte())
        val size = readInt()
        assertTrue(size in 1..16384)
        return ByteArray(size).also { readFully(it) }
    }

    private fun DataOutputStream.frame(type: Int, payload: ByteArray) {
        writeByte(1)
        writeByte(type)
        writeInt(payload.size)
        write(payload)
        flush()
    }
}
