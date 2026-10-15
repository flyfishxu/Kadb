@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.flyfishxu.kadb.transport

import cnames.structs.kadb_io
import com.flyfishxu.kadb.TcpKeepAlive
import com.flyfishxu.kadb.cert.AdbKeyPair
import com.flyfishxu.kadb.exception.AdbPairAuthException
import com.flyfishxu.kadb.nativebridge.*
import kotlinx.cinterop.*
import okio.Closeable
import okio.IOException
import kotlin.native.ref.createCleaner
import kotlin.time.TimeSource

internal actual interface TransportChannel : Closeable {
    actual val mayHaveStaleStreamPackets: Boolean get() = false
    actual val isOpen: Boolean
}

/** Owns the native socket for both plaintext ADB and its in-place STLS upgrade. */
internal class PosixTransportChannel private constructor(private val handle: CPointer<kadb_io>) : TransportChannel {
    @Suppress("unused")
    private val cleaner = createCleaner(handle) { kadb_io_free(it) }

    /** Adopts an accepted socket; ownership transfers even if configuration fails. */
    internal constructor(fd: Int) : this(nativeIoCall { error ->
        kadb_io_adopt(fd, error, ERROR_CAPACITY.convert()) ?: throw nativeIoError(error)
    })

    override val isOpen: Boolean get() = kadb_io_is_open(handle) != 0

    override fun close() = kadb_io_close(handle)

    internal fun read(bytes: ByteArray, offset: Int, count: Int, timeoutMs: Long): Int {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        if (count == 0) return 0
        return nativeIoCall { error ->
            val read = bytes.usePinned { pinned ->
                kadb_io_read(handle, pinned.addressOf(offset).reinterpret(), count, timeoutMs,
                    error, ERROR_CAPACITY.convert())
            }
            if (read < 0) throw nativeIoError(error)
            if (read == 0) -1 else read
        }
    }

    internal fun write(bytes: ByteArray, offset: Int, count: Int, timeoutMs: Long) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        if (count == 0) return
        val started = TimeSource.Monotonic.markNow()
        nativeIoCall { error ->
            bytes.usePinned { pinned ->
                var written = 0
                while (written < count) {
                    val remainingTimeout = if (timeoutMs > 0) {
                        (timeoutMs - started.elapsedNow().inWholeMilliseconds).also {
                            if (it <= 0) throw IOException("Socket operation timed out")
                        }
                    } else 0L
                    val result = kadb_io_write(handle, pinned.addressOf(offset + written).reinterpret(),
                        count - written, remainingTimeout, error, ERROR_CAPACITY.convert())
                    if (result <= 0) throw nativeIoError(error)
                    written += result
                }
            }
        }
    }

    internal fun startTls(keys: List<AdbKeyPair>, timeoutMs: Long) {
        nativeIoCall { error ->
            for (key in keys) {
                key.privateKeyPem.usePinned { privateKey ->
                    key.certificatePem.usePinned { certificate ->
                        val result = kadb_io_add_identity(handle,
                            privateKey.addressOf(0).reinterpret(), key.privateKeyPem.size.convert(),
                            certificate.addressOf(0).reinterpret(), key.certificatePem.size.convert(),
                            key.adbTlsFingerprintHex(), error, ERROR_CAPACITY.convert())
                        if (result != 1) throw nativeIoError(error)
                    }
                }
            }
            if (kadb_io_start_tls(handle, timeoutMs, error, ERROR_CAPACITY.convert()) != 1) {
                throw nativeIoError(error)
            }
        }
    }

    internal fun exportPairingKey(): ByteArray = ByteArray(64).also { bytes ->
        nativeIoCall { error ->
            bytes.usePinned { pinned ->
                if (kadb_io_export_pairing_key(handle, pinned.addressOf(0).reinterpret(), bytes.size.convert(),
                        error, ERROR_CAPACITY.convert()) != 1) throw nativeIoError(error)
            }
        }
    }

    companion object {
        internal fun connect(host: String, port: Int, timeoutMs: Long, keepAlive: TcpKeepAlive? = null): PosixTransportChannel {
            require(host.isNotBlank() && '\u0000' !in host) { "Host must not be empty or contain NUL" }
            require(port in 1..65535) { "Port must be between 1 and 65535" }
            require(timeoutMs >= 0) { "Timeout must not be negative" }
            return nativeIoCall { error ->
                PosixTransportChannel(kadb_io_connect(host.removeSurrounding("[", "]"), port, timeoutMs,
                    keepAlive?.idleSeconds ?: 0, keepAlive?.intervalSeconds ?: 0, keepAlive?.probeCount ?: 0,
                    error, ERROR_CAPACITY.convert()) ?: throw nativeIoError(error))
            }
        }
    }
}

private const val ERROR_CAPACITY = 512

private inline fun <T> nativeIoCall(block: (CPointer<ByteVar>) -> T): T = memScoped {
    val error = allocArray<ByteVar>(ERROR_CAPACITY)
    error[0] = 0
    block(error)
}

private fun nativeIoError(error: CPointer<ByteVar>): IOException {
    val message = error.toKString().ifEmpty { "Socket operation failed" }
    val normalized = message.lowercase().replace(' ', '_')
    return if (listOf("certificate_required", "unknown_ca", "access_denied", "certificate_unknown")
            .any(normalized::contains)) AdbPairAuthException() else IOException(message)
}
