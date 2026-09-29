package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader
import com.flyfishxu.kadb.core.AdbWriter
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class UsbPacketChannelTest {
    @Test fun bufferedWriterSeparatesHeaderPayloadAndZlp() {
        for (size in listOf(0, 1, 511, 512, 513, 16_384, 16_385, 1024 * 1024)) {
            val io = FakeUsb()
            val payload = ByteArray(size) { (it % 251).toByte() }
            val writer = AdbWriter(UsbPacketChannel(io).asOkioSink())
            writer.write(AdbProtocol.CMD_WRTE, 1, 2, payload, 0, size)
            assertEquals(24, io.sent.first().size)
            assertContentEquals(payload, io.sent.drop(1).fold(ByteArray(0)) { all, part -> all + part })
            assertTrue(io.sent.all { it.size <= 16_384 })
            assertEquals(size > 0 && size % 512 == 0, io.sent.last().isEmpty())
        }
    }

    @Test fun arbitraryWritesReassembleFramesWithoutMergingUsbTransfers(): Unit = runBlocking {
        val io = FakeUsb()
        val channel = UsbPacketChannel(io)
        val first = frame(ByteArray(512) { 7 })
        val second = frame(byteArrayOf(3, 4))
        for (part in (first + second).toList().chunked(13)) {
            channel.writeExactly(ByteBuffer.wrap(part.toByteArray()), 1, TimeUnit.SECONDS)
        }
        assertEquals(listOf(24, 512, 0, 24, 2), io.sent.map { it.size })
        assertContentEquals(first + second, io.sent.fold(ByteArray(0)) { all, bytes -> all + bytes })
    }

    @Test fun alignedIncomingPayloadDoesNotWaitForNonexistentZlp() {
        for (size in listOf(0, 1, 512, 16_384, 16_385)) {
            val io = FakeUsb()
            val payload = ByteArray(size) { (it % 127).toByte() }
            io.incoming.add(frame(payload).copyOfRange(0, 24))
            payload.toList().chunked(16_384).forEach { io.incoming.add(it.toByteArray()) }
            io.incoming.add(frame(ByteArray(0)))
            val reader = AdbReader(UsbPacketChannel(io).asOkioSource())
            assertContentEquals(payload, reader.readMessage().payload)
            assertContentEquals(ByteArray(0), reader.readMessage().payload)
            assertTrue(io.incoming.isEmpty())
        }
    }

    @Test fun invalidHeadersAndPartialWritesCloseTransport(): Unit = runBlocking {
        val malformed = FakeUsb().apply {
            incoming.add(frame(ByteArray(0)).also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Int.MAX_VALUE)
            })
        }
        assertFailsWith<IOException> { AdbReader(UsbPacketChannel(malformed).asOkioSource()).readMessage() }
        assertFalse(malformed.isOpen)
        val partial = FakeUsb().apply { partialWrites = true }
        assertFailsWith<IOException> {
            UsbPacketChannel(partial).writeExactly(ByteBuffer.wrap(frame(byteArrayOf(1))), 0, TimeUnit.MILLISECONDS)
        }
        assertFalse(partial.isOpen)
        assertEquals(1, partial.sent.size) // Do not retry a write with uncertain delivery.
    }

    @Test fun closeUnblocksAnIdleReader() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val io = object : FakeUsb() {
            override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
                started.countDown()
                check(stopped.await(2, TimeUnit.SECONDS))
                throw IOException("USB disconnected")
            }
            override fun close() { super.close(); stopped.countDown() }
        }
        val channel = UsbPacketChannel(io)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Boolean> {
                assertFailsWith<IOException> { AdbReader(channel.asOkioSource()).readMessage() }
                true
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            channel.close()
            assertTrue(pending.get(1, TimeUnit.SECONDS))
        } finally { channel.close(); worker.shutdownNow() }
    }

    private fun frame(payload: ByteArray): ByteArray = ByteBuffer.allocate(24 + payload.size)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(AdbProtocol.CMD_WRTE).putInt(1).putInt(2)
        .putInt(payload.size).putInt(0).putInt(AdbProtocol.CMD_WRTE.inv()).put(payload).array()

    private open class FakeUsb : UsbBulkIo {
        override val inputPacketSize = 512
        override val outputPacketSize = 512
        override var isOpen = true
        val sent = mutableListOf<ByteArray>()
        val incoming = ArrayDeque<ByteArray>()
        var partialWrites = false
        override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
            val bytes = incoming.removeFirst()
            assertTrue(buffer.size >= bytes.size)
            // Oversized reads of packet-aligned payloads would wait for more data on hardware.
            if (bytes.size % inputPacketSize == 0) assertEquals(bytes.size, buffer.size)
            bytes.copyInto(buffer)
            return bytes.size
        }
        override fun send(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            sent.add(buffer.copyOfRange(offset, offset + length))
            return if (partialWrites) length - 1 else length
        }
        override fun close() { isOpen = false }
    }
}
