package com.flyfishxu.kadb.transport

import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout

private const val BUFFER_SIZE = 64 * 1024

internal actual fun TransportChannel.asOkioSource(readTimeoutMs: Long): Source {
    val channel = this as PosixTransportChannel
    return object : Source {
        private val scratch = ByteArray(BUFFER_SIZE)

        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0) { "byteCount must not be negative" }
            if (byteCount == 0L) return 0
            val count = channel.read(scratch, 0, minOf(byteCount, BUFFER_SIZE.toLong()).toInt(), readTimeoutMs)
            if (count < 0) return -1
            sink.write(scratch, 0, count)
            return count.toLong()
        }

        // Native Okio Timeout has no configurable deadline API; the transport enforces readTimeoutMs.
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit // The connection owns transport lifetime, like Android/JVM.
    }
}

internal actual fun TransportChannel.asOkioSink(writeTimeoutMs: Long): Sink {
    val channel = this as PosixTransportChannel
    return object : Sink {
        private val scratch = ByteArray(BUFFER_SIZE)

        override fun write(source: Buffer, byteCount: Long) {
            require(byteCount >= 0 && byteCount <= source.size) { "Invalid byteCount" }
            var remaining = byteCount
            while (remaining > 0) {
                val count = source.read(scratch, 0, minOf(remaining, BUFFER_SIZE.toLong()).toInt())
                check(count > 0)
                channel.write(scratch, 0, count, writeTimeoutMs)
                remaining -= count
            }
        }

        override fun flush() = Unit
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }
}
