package com.flyfishxu.kadb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KadbDelayedAckTest {
    @Test
    fun delayedAckWaitsForEnoughWindowBeforeSendingNextPayload() {
        val expected = ByteArray(1024 * 1024) { index -> ((index * 31 + 17) and 0xff).toByte() }
        val tempDir = createTempDirectory("kadb-delayed-ack").toFile()
        val source = File(tempDir, "source.bin").apply { writeBytes(expected) }
        val received = AtomicReference<ByteArray?>(null)
        val serverFailure = AtomicReference<Throwable?>(null)

        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val serverThread = thread(name = "kadb-delayed-ack-peer") {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = IO_TIMEOUT_MS
                            val input = DataInputStream(socket.getInputStream().buffered())
                            val output = socket.getOutputStream().buffered()

                            val connect = readPacket(input)
                            check(connect.command == CMD_CNXN)
                            writePacket(
                                output,
                                CMD_CNXN,
                                ADB_VERSION,
                                ADB_MAX_DATA,
                                "device::features=delayed_ack,sendrecv_v2".encodeToByteArray()
                            )

                            val open = readPacket(input)
                            check(open.command == CMD_OPEN)
                            val clientId = open.arg0
                            val serverId = 1
                            writePacket(output, CMD_OKAY, serverId, clientId, littleEndianInt(INITIAL_WINDOW))

                            val syncBytes = ByteArrayOutputStream()
                            var firstWrite = true
                            var parsed: ParsedSync? = null
                            while (parsed == null) {
                                val write = readPacket(input)
                                check(write.command == CMD_WRTE)
                                syncBytes.write(write.payload)

                                if (firstWrite) {
                                    firstWrite = false
                                    socket.soTimeout = WINDOW_PROBE_TIMEOUT_MS
                                    try {
                                        val premature = readPacket(input)
                                        error(
                                            "Client exceeded delayed-ACK window before credit arrived: " +
                                                "command=${premature.command.toString(16)} bytes=${premature.payload.size}"
                                        )
                                    } catch (_: SocketTimeoutException) {
                                    // Expected: the setup WRTE leaves less credit than the next DATA WRTE needs.
                                    } finally {
                                        socket.soTimeout = IO_TIMEOUT_MS
                                    }
                                }

                                writePacket(
                                    output,
                                    CMD_OKAY,
                                    serverId,
                                    clientId,
                                    littleEndianInt(write.payload.size)
                                )
                                parsed = parseCompletedSend(syncBytes.toByteArray())
                            }

                            received.set(parsed.data)
                            writePacket(output, CMD_WRTE, serverId, clientId, syncOkay())

                            while (true) {
                                val packet = readPacket(input)
                                when (packet.command) {
                                    CMD_OKAY -> Unit
                                    CMD_WRTE -> writePacket(
                                        output,
                                        CMD_OKAY,
                                        serverId,
                                        clientId,
                                        littleEndianInt(packet.payload.size)
                                    )

                                    CMD_CLSE -> {
                                        writePacket(output, CMD_CLSE, serverId, clientId)
                                        break
                                    }

                                    else -> error("Unexpected command: ${packet.command.toString(16)}")
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        serverFailure.set(error)
                    }
                }

                var clientFailure: Throwable? = null
                try {
                    Kadb.create(
                        host = "127.0.0.1",
                        port = server.localPort,
                        connectTimeout = IO_TIMEOUT_MS,
                        socketTimeout = IO_TIMEOUT_MS,
                        options = KadbOptions(DelayedAckMode.ENABLED)
                    ).use { kadb ->
                        kadb.push(source, "/data/local/tmp/source.bin")
                    }
                } catch (error: Throwable) {
                    clientFailure = error
                }

                serverThread.join(IO_TIMEOUT_MS.toLong())
                assertEquals(false, serverThread.isAlive, "Synthetic delayed-ACK peer did not finish")
                assertNull(serverFailure.get(), serverFailure.get()?.stackTraceToString())
                assertNull(clientFailure, clientFailure?.stackTraceToString())
                assertContentEquals(expected, checkNotNull(received.get()))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun parseCompletedSend(bytes: ByteArray): ParsedSync? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 8) return null
        check(buffer.readId() == "SND2")
        val pathLength = buffer.int
        if (pathLength < 0 || buffer.remaining() < pathLength + 12) return null
        buffer.position(buffer.position() + pathLength)
        check(buffer.readId() == "SND2")
        buffer.int // mode
        buffer.int // flags

        val data = ByteArrayOutputStream()
        while (buffer.remaining() >= 8) {
            val id = buffer.readId()
            val argument = buffer.int
            when (id) {
                "DATA" -> {
                    if (argument < 0 || buffer.remaining() < argument) return null
                    val chunk = ByteArray(argument)
                    buffer.get(chunk)
                    data.write(chunk)
                }

                "DONE" -> return ParsedSync(data.toByteArray())
                else -> error("Unexpected sync id: $id")
            }
        }
        return null
    }

    private fun readPacket(input: DataInputStream): AdbPacket {
        val command = input.readIntLe()
        val arg0 = input.readIntLe()
        val arg1 = input.readIntLe()
        val length = input.readIntLe()
        input.readIntLe() // checksum
        val magic = input.readIntLe()
        check(magic == command.inv())
        check(length in 0..ADB_MAX_DATA)
        return AdbPacket(command, arg0, arg1, input.readNBytes(length))
    }

    private fun writePacket(
        output: java.io.OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray = ByteArray(0)
    ) {
        val packet = ByteBuffer.allocate(24 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(0)
            .putInt(command.inv())
            .put(payload)
            .array()
        output.write(packet)
        output.flush()
    }

    private fun DataInputStream.readIntLe(): Int = Integer.reverseBytes(readInt())

    private fun ByteBuffer.readId(): String {
        val id = ByteArray(4)
        get(id)
        return id.decodeToString()
    }

    private fun littleEndianInt(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private fun syncOkay(): ByteArray = ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("OKAY".encodeToByteArray())
        .putInt(0)
        .array()

    private data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)
    private data class ParsedSync(val data: ByteArray)

    private companion object {
        const val CMD_CNXN = 0x4e584e43
        const val CMD_OPEN = 0x4e45504f
        const val CMD_OKAY = 0x59414b4f
        const val CMD_CLSE = 0x45534c43
        const val CMD_WRTE = 0x45545257
        const val ADB_VERSION = 0x01000001
        const val ADB_MAX_DATA = 1024 * 1024
        // Large enough for an 8,200-byte DATA WRTE, but not for that packet plus the
        // preceding 46-byte SND2 setup WRTE. This exposes partial-window oversends.
        const val INITIAL_WINDOW = 8_224
        const val IO_TIMEOUT_MS = 5_000
        const val WINDOW_PROBE_TIMEOUT_MS = 250
    }
}
