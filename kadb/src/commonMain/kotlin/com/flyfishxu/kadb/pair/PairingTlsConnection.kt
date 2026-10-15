package com.flyfishxu.kadb.pair

import com.flyfishxu.kadb.cert.AdbKeyPair
import okio.Closeable

/** Unbuffered TLS I/O and session binding for the common pairing state machine. */
internal expect class PairingTlsConnection(host: String, port: Int, keyPair: AdbKeyPair) : Closeable {
    fun start()
    fun exportKeyingMaterial(): ByteArray
    fun readFully(bytes: ByteArray)
    fun write(bytes: ByteArray)
    override fun close()
}
