package com.flyfishxu.kadb.shell

import com.flyfishxu.kadb.stream.AdbStream
import java.io.IOException
import kotlin.Throws

class AdbShellStream(
    private val stream: AdbStream
) : AutoCloseable {
    @Throws(IOException::class)
    fun readAll(): AdbShellResponse {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        while (true) {
            when (val packet = read()) {
                is AdbShellPacket.Exit -> {
                    val exitCode = packet.payload[0].toInt()
                    return AdbShellResponse(output.toString(), errorOutput.toString(), exitCode)
                }

                is AdbShellPacket.StdOut -> {
                    output.append(String(packet.payload))
                }

                is AdbShellPacket.StdError -> {
                    errorOutput.append(String(packet.payload))
                }

                else -> {
                    throw IllegalStateException("Unexpected shell packet: $packet")
                }
            }
        }
    }

    @Throws(IOException::class)
    fun read(): AdbShellPacket {
        stream.source.apply {
            val id = checkId(readByte().toInt())
            val length = checkLength(id, readIntLe())
            val payload = readByteArray(length.toLong())
            return when (id) {
                AdbShellPacketV2.ID_STDOUT -> AdbShellPacket.StdOut(payload)
                AdbShellPacketV2.ID_STDERR -> AdbShellPacket.StdError(payload)
                AdbShellPacketV2.ID_EXIT -> AdbShellPacket.Exit(payload)
                AdbShellPacketV2.ID_CLOSE_STDIN -> throw IOException("Todo: ID_CLOSE_STDIN")
                AdbShellPacketV2.ID_WINDOW_SIZE_CHANGE -> throw IOException("Todo: ID_WINDOW_SIZE_CHANGE")
                AdbShellPacketV2.ID_INVALID -> throw IOException("Todo: ID_INVALID")
                else -> throw IllegalArgumentException("Invalid shell packet id: $id")
            }
        }
    }

    @Throws(IOException::class)
    fun write(string: String) {
        write(AdbShellPacketV2.ID_STDIN, string.toByteArray())
    }

    @Throws(IOException::class)
    fun write(id: Int, payload: ByteArray? = null) {
        stream.sink.apply {
            writeByte(id)
            writeIntLe(payload?.size ?: 0)
            if (payload != null) write(payload)
            flush()
        }
    }

    override fun close() {
        stream.close()
    }

    private fun checkId(id: Int): Int {
        check(id == AdbShellPacketV2.ID_STDOUT || id == AdbShellPacketV2.ID_STDERR || id == AdbShellPacketV2.ID_EXIT) {
            "Invalid shell packet id: $id"
        }
        return id
    }

    private fun checkLength(id: Int, length: Int): Int {
        check(length >= 0) { "Shell packet length must be >= 0: $length" }
        check(id != AdbShellPacketV2.ID_EXIT || length == 1) { "Shell exit packet does not have payload length == 1: $length" }
        return length
    }
}