package com.flyfishxu.kadb.pair

import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import com.flyfishxu.kadb.cert.AndroidPubkey
import com.flyfishxu.kadb.cert.CertUtils
import com.flyfishxu.kadb.cert.KadbCertPolicy
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt

/** Independent JVM server for the opt-in real iOS Simulator pairing interoperability test. */
object IosPairingPeer {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Pass the ready-file path" }
        val identity = CertUtils.generateNewIdentity(KadbCertPolicy())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("peer", identity.keyPair.privateKey, charArrayOf(), arrayOf(identity.keyPair.certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, charArrayOf())
        }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, type: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, type: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(managers.keyManagers, arrayOf(trust), null)
        }
        (context.serverSocketFactory.createServerSocket(0, 2, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).use { server ->
            server.enabledProtocols = arrayOf("TLSv1.3")
            server.needClientAuth = true
            server.soTimeout = 60_000
            val ready = Path.of(args[0])
            val temporary = ready.resolveSibling("${ready.fileName}.tmp")
            Files.writeString(temporary, server.localPort.toString())
            Files.move(temporary, ready)
            repeat(2) { index ->
                (server.accept() as SSLSocket).use { socket -> exchange(socket, wrongCode = index == 1) }
            }
        }
        println("PASS: iOS client paired with JVM TLS/SPAKE2/JCA peer; wrong pairing code rejected")
    }

    private fun exchange(socket: SSLSocket, wrongCode: Boolean) {
        socket.soTimeout = 10_000
        socket.startHandshake()
        check(socket.session.protocol == "TLSv1.3")
        val clientCertificate = socket.session.peerCertificates.single() as X509Certificate
        val publicKey = AndroidPubkey.encodeWithName(clientCertificate.publicKey as RSAPublicKey, "kadb-ios-interop")
        val binding = Conscrypt.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)
        val bob = Spake2Context(Spake2Role.Bob,
            "adb pair server\u0000".encodeToByteArray(), "adb pair client\u0000".encodeToByteArray())
        try {
            val message = bob.generateMessage("123456".encodeToByteArray() + binding)
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            val clientMessage = input.frame(0)
            output.frame(0, message)
            val key = deriveKey(bob.processMessage(clientMessage))
            try {
                val encrypted = input.frame(1)
                if (wrongCode) {
                    val failure = runCatching { crypt(Cipher.DECRYPT_MODE, key, encrypted) }.exceptionOrNull()
                    check(failure is AEADBadTagException) { "Wrong pairing code unexpectedly authenticated: $failure" }
                } else {
                    val info = crypt(Cipher.DECRYPT_MODE, key, encrypted)
                    check(info.size == 8192 && info[0] == 0.toByte())
                    check(info.copyOfRange(1, 1 + publicKey.size).contentEquals(publicKey))
                    check(info.drop(1 + publicKey.size).all { it == 0.toByte() })
                    val response = ByteArray(8192)
                    response[0] = 1
                    "adb-jvm-peer-guid\u0000".encodeToByteArray().copyInto(response, 1)
                    output.frame(1, crypt(Cipher.ENCRYPT_MODE, key, response))
                }
            } finally {
                key.fill(0)
            }
        } finally {
            bob.destroy()
            binding.fill(0)
        }
    }

    private fun deriveKey(material: ByteArray): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val extracted = hmac.doFinal(material)
        material.fill(0)
        return try {
            hmac.init(SecretKeySpec(extracted, "HmacSHA256"))
            hmac.doFinal("adb pairing_auth aes-128-gcm key".encodeToByteArray() + byteArrayOf(1)).copyOf(16)
        } finally {
            extracted.fill(0)
        }
    }

    private fun crypt(mode: Int, key: ByteArray, bytes: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(0).array()
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(bytes)
        }

    private fun DataInputStream.frame(type: Int): ByteArray {
        check(readUnsignedByte() == 1 && readUnsignedByte() == type)
        val size = readInt()
        check(size in 1..16384)
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.frame(type: Int, payload: ByteArray) {
        writeByte(1)
        writeByte(type)
        writeInt(payload.size)
        write(payload)
        flush()
    }
}
