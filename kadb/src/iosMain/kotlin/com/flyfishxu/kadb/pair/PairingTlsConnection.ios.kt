package com.flyfishxu.kadb.pair

import com.flyfishxu.kadb.cert.AdbKeyPair
import com.flyfishxu.kadb.transport.PosixTransportChannel
import okio.Closeable
import okio.EOFException

internal actual class PairingTlsConnection actual constructor(
    private val host: String, private val port: Int, private val keyPair: AdbKeyPair
) : Closeable {
    private var channel: PosixTransportChannel? = null

    actual fun start() {
        check(channel == null) { "Pairing TLS already started" }
        val socket = PosixTransportChannel.connect(host, port, 0)
        channel = socket
        try {
            socket.startTls(listOf(keyPair), 0)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    actual fun exportKeyingMaterial(): ByteArray = checkNotNull(channel).exportPairingKey()

    actual fun readFully(bytes: ByteArray) {
        val socket = checkNotNull(channel)
        var offset = 0
        while (offset < bytes.size) {
            val count = socket.read(bytes, offset, bytes.size - offset, 0)
            if (count < 0) throw EOFException("Pairing TLS connection ended")
            offset += count
        }
    }

    actual fun write(bytes: ByteArray) = checkNotNull(channel).write(bytes, 0, bytes.size, 0)

    actual override fun close() {
        channel?.close()
        channel = null
    }
}
