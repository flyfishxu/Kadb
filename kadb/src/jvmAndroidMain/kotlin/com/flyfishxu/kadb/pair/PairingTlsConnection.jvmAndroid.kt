package com.flyfishxu.kadb.pair

import com.flyfishxu.kadb.cert.AdbKeyPair
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import okio.Closeable
import okio.IOException

internal actual class PairingTlsConnection actual constructor(
    private val host: String, private val port: Int, keyPair: AdbKeyPair
) : Closeable {
    private val sslContext = SslUtils.getSslContext(keyPair)
    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    actual fun start() {
        val tcp = Socket(host, port)
        tcp.tcpNoDelay = true
        val tls = sslContext.socketFactory.createSocket(tcp, host, port, true) as SSLSocket
        tls.startHandshake()
        socket = tls
        input = DataInputStream(tls.inputStream)
        output = DataOutputStream(tls.outputStream)
    }

    actual fun exportKeyingMaterial(): ByteArray {
        return try {
            val method = getConscryptClass().getMethod(
                "exportKeyingMaterial", SSLSocket::class.java, String::class.java,
                ByteArray::class.java, Int::class.javaPrimitiveType
            )
            method.invoke(null, socket!!, "adb-label\u0000", null, 64) as ByteArray
        } catch (error: SSLException) {
            throw error
        } catch (error: Throwable) {
            throw SSLException(error)
        }
    }

    actual fun readFully(bytes: ByteArray) = input!!.readFully(bytes)
    actual fun write(bytes: ByteArray) = output!!.write(bytes)

    actual override fun close() {
        try {
            input?.close()
        } catch (_: IOException) {
        }
        try {
            output?.close()
        } catch (_: IOException) {
        }
    }
}

internal expect fun PairingTlsConnection.getConscryptClass(): Class<*>
