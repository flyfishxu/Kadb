package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.core.AdbProtocol
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** One USB transfer, including its short-packet boundary. Reads may block until cancelled. */
internal interface UsbBulkIo : Closeable {
    val inputPacketSize: Int
    val outputPacketSize: Int
    val isOpen: Boolean
    fun receive(buffer: ByteArray, timeoutMs: Int): Int
    fun send(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
}

/** Reconstruct ADB frames across arbitrary Okio writes; keep header and payload USB transfers separate. */
internal class UsbPacketChannel(private val io: UsbBulkIo) : TransportChannel {
    override val mayHaveStaleStreamPackets = true
    private var incoming = ByteBuffer.allocate(0)
    private var outgoing = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
    private var outgoingHeader: ByteArray? = null
    override val localAddress get() = null
    override val remoteAddress get() = null
    override val isOpen get() = io.isOpen

    override suspend fun read(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int = guarded {
        if (!dst.hasRemaining()) return@guarded 0
        if (!incoming.hasRemaining()) {
            val header = ByteArray(io.inputPacketSize.coerceAtLeast(HEADER))
            val count = io.receive(header, timeoutMillis(timeout, unit))
            if (count != HEADER) throw IOException("Invalid USB ADB header length: $count")
            val length = payloadLength(header)
            val frame = ByteArray(HEADER + length)
            header.copyInto(frame, endIndex = HEADER)
            var offset = HEADER
            while (offset < frame.size) {
                val remaining = frame.size - offset
                // adbd does not send a ZLP after aligned payloads. Never request beyond
                // the rounded payload size, or an exact packet-sized payload can hang.
                val size = minOf(CHUNK, roundUp(remaining, io.inputPacketSize))
                val bytes = ByteArray(size)
                val read = io.receive(bytes, timeoutMillis(timeout, unit))
                if (read <= 0 || read > remaining) throw IOException("Invalid USB ADB payload length: $read")
                bytes.copyInto(frame, offset, 0, read)
                offset += read
            }
            incoming = ByteBuffer.wrap(frame)
        }
        val count = minOf(dst.remaining(), incoming.remaining())
        val slice = incoming.duplicate().apply { limit(position() + count) }
        dst.put(slice)
        incoming.position(incoming.position() + count)
        count
    }

    override suspend fun write(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int = guarded {
        val count = src.remaining()
        while (src.hasRemaining()) {
            val size = minOf(src.remaining(), outgoing.remaining())
            val slice = src.duplicate().apply { limit(position() + size) }
            outgoing.put(slice)
            src.position(src.position() + size)
            if (outgoing.hasRemaining()) continue
            if (outgoingHeader == null) {
                val header = outgoing.array()
                val length = payloadLength(header)
                outgoingHeader = header
                outgoing = ByteBuffer.allocate(length)
                if (length > 0) continue
            }
            val timeoutMs = timeoutMillis(timeout, unit).takeIf { it > 0 } ?: 10_000
            send(outgoingHeader!!, timeoutMs)
            val payload = outgoing.array()
            if (payload.isNotEmpty()) send(payload, timeoutMs)
            outgoingHeader = null
            outgoing = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        }
        count
    }

    private fun send(bytes: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(CHUNK, bytes.size - offset)
            if (io.send(bytes, offset, length, timeoutMs) != length) {
                // A partial write has uncertain framing; never replay it.
                throw IOException("USB ADB write failed or was incomplete")
            }
            offset += length
        }
        if (bytes.size % io.outputPacketSize == 0 && io.send(ByteArray(0), 0, 0, timeoutMs) != 0) {
            throw IOException("USB ADB zero-length packet failed")
        }
    }

    override suspend fun readExactly(dst: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (dst.hasRemaining()) read(dst, timeout, unit)
    }
    override suspend fun writeExactly(src: ByteBuffer, timeout: Long, unit: TimeUnit) {
        write(src, timeout, unit)
    }
    override suspend fun shutdownInput() = close()
    override suspend fun shutdownOutput() = close()
    override fun close() = io.close()

    private inline fun <T> guarded(block: () -> T): T {
        if (!isOpen) throw IOException("USB transport closed")
        try { return block() } catch (error: Throwable) {
            runCatching { close() }
            throw error
        }
    }

    private fun payloadLength(header: ByteArray): Int {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(20) != buffer.getInt(0).inv()) throw IOException("Invalid USB ADB magic")
        return buffer.getInt(12).also {
            if (it !in 0..AdbProtocol.CONNECT_MAXDATA) throw IOException("Invalid USB ADB payload size: $it")
        }
    }

    private fun timeoutMillis(timeout: Long, unit: TimeUnit): Int =
        if (timeout <= 0) 0 else unit.toMillis(timeout).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    private fun roundUp(size: Int, packet: Int) = ((size + packet - 1) / packet) * packet

    private companion object {
        const val HEADER = AdbProtocol.ADB_HEADER_LENGTH
        // Android before API 28 truncates bulk transfers above 16 KiB.
        const val CHUNK = 16 * 1024
    }
}
