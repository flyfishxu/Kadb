package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.cert.InMemoryPrivateKeyStore
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.core.AdbConnection
import com.flyfishxu.kadb.core.AdbProtocol
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbHandshakeTest {
    @Test fun reconnectDiscardsOldStreamPacketsBeforeNewHandshake() = runBlocking {
        val io = PacketUsb().apply {
            packet(AdbProtocol.CMD_CLSE)
            packet(AdbProtocol.CMD_WRTE, "old output".toByteArray())
            packet(AdbProtocol.CMD_OKAY)
            packet(AdbProtocol.CMD_CNXN, "device::features=shell_v2;\u0000".toByteArray())
        }
        val channel = UsbPacketChannel(io)
        val (connection, _) = connect(channel)
        try { assertTrue(connection.supportsFeature("shell_v2")) }
        finally { connection.close(); channel.close() }
    }

    @Test fun freshTransportsStillRejectUnexpectedStreamPackets() = runBlocking {
        val io = PacketUsb().apply { packet(AdbProtocol.CMD_CLSE) }
        val channel = object : TransportChannel by UsbPacketChannel(io) {
            override val mayHaveStaleStreamPackets = false
        }
        assertFailsWith<IOException> { connect(channel) }
        assertFalse(io.isOpen)
    }

    @Test fun stalePacketFloodIsBounded() = runBlocking {
        val io = PacketUsb().apply { repeat(65) { packet(AdbProtocol.CMD_CLSE) } }
        assertFailsWith<IOException> { connect(UsbPacketChannel(io)) }
        assertFalse(io.isOpen)
    }

    private suspend fun connect(channel: TransportChannel): Pair<AdbConnection, TransportChannel> {
        KadbCert.configure(InMemoryPrivateKeyStore())
        return AdbConnection.connect("", 0, KadbCert.currentKeySet(), transportConnector = { channel })
    }

    private class PacketUsb : UsbBulkIo {
        override val inputPacketSize = 512
        override val outputPacketSize = 512
        @Volatile override var isOpen = true
        private val packets = ArrayDeque<ByteArray>()
        fun packet(command: Int, payload: ByteArray = byteArrayOf()) {
            packets.add(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(AdbProtocol.A_VERSION).putInt(4096)
                .putInt(payload.size).putInt(0).putInt(command.inv()).array())
            if (payload.isNotEmpty()) packets.add(payload)
        }
        override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
            val bytes = packets.poll() ?: throw IOException("End of fixture")
            bytes.copyInto(buffer)
            return bytes.size
        }
        override fun send(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int) = length
        override fun close() { isOpen = false }
    }
}
