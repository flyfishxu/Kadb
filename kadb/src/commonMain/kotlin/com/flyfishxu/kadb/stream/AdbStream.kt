/*
 * Copyright (c) 2021 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.flyfishxu.kadb.stream

import com.flyfishxu.kadb.core.AdbMessage
import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbWriter
import com.flyfishxu.kadb.queue.AdbMessageQueue
import okio.*
import java.io.IOException
import java.lang.Integer.min
import java.nio.ByteBuffer

class AdbStream internal constructor(
    private val messageQueue: AdbMessageQueue,
    private val adbWriter: AdbWriter,
    private val outboundMaxPayloadSize: Int,
    private val localId: Int,
    private val remoteId: Int,
    private val delayedAckEnabled: Boolean = false,
    private val initialAvailableSendBytes: Long = 0
) : AutoCloseable {
    private var isClosed = false
    val source = object : Source {
        private var message: AdbMessage? = null
        private var bytesRead = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            val message = message() ?: return -1

            val bytesRemaining = message.payloadLength - bytesRead
            // Clamp before conversion to avoid Long->Int overflow on very large read requests.
            val bytesToRead = byteCount
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtMost(bytesRemaining)

            sink.write(message.payload, bytesRead, bytesToRead)

            bytesRead += bytesToRead
            if (delayedAckEnabled && bytesToRead > 0) {
                adbWriter.writeOkay(localId, remoteId, bytesToRead)
            }

            check(bytesRead <= message.payloadLength)

            if (bytesRead == message.payloadLength) {
                this.message = null
                if (!delayedAckEnabled) {
                    adbWriter.writeOkay(localId, remoteId)
                }
            }

            return bytesToRead.toLong()
        }

        private fun message(): AdbMessage? {
            message?.let { return it }
            val nextMessage = nextMessage(AdbProtocol.CMD_WRTE)
            message = nextMessage
            bytesRead = 0
            return nextMessage
        }

        override fun close() {}

        override fun timeout() = Timeout.NONE
    }.buffer()

    val sink = object : Sink {
        private val buffer = ByteBuffer.allocate(outboundMaxPayloadSize)
        private var availableSendBytes = initialAvailableSendBytes

        override fun write(source: Buffer, byteCount: Long) {
            var remainingBytes = byteCount
            while (remainingBytes > 0L) {
                remainingBytes -= writeToBuffer(source, remainingBytes)
            }
        }

        private fun writeToBuffer(source: BufferedSource, byteCount: Long): Int {
            val bytesToWrite = min(
                buffer.remaining(),
                byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val bytesWritten = source.read(buffer.array(), buffer.position(), bytesToWrite)
            if (bytesWritten < 0) throw java.io.EOFException("EOF while writing ADB stream")

            buffer.position(buffer.position() + bytesWritten)
            if (buffer.remaining() == 0) flush()

            return bytesWritten
        }

        override fun flush() {
            val payloadLength = buffer.position()
            if (payloadLength == 0) return
            if (delayedAckEnabled) {
                while (availableSendBytes <= 0) {
                    availableSendBytes += awaitAckBytes().toLong()
                }
            }
            adbWriter.writeWrite(localId, remoteId, buffer.array(), 0, payloadLength)
            if (delayedAckEnabled) {
                availableSendBytes -= payloadLength.toLong()
            }
            buffer.clear()
            if (!delayedAckEnabled && nextMessage(AdbProtocol.CMD_OKAY) == null) {
                throw IOException("ADB stream closed before OKAY for localId: ${localId.toString(16)}")
            }
        }

        override fun close() {}

        override fun timeout() = Timeout.NONE
    }.buffer()

    private fun awaitAckBytes(): Int {
        val message = nextMessage(AdbProtocol.CMD_OKAY)
            ?: throw IOException("ADB stream closed before delayed ACK for localId: ${localId.toString(16)}")
        return decodeOkayAckBytes(message)
    }

    private fun decodeOkayAckBytes(message: AdbMessage): Int {
        require(message.command == AdbProtocol.CMD_OKAY) { "Expected OKAY message, got ${message.command}" }
        return when (message.payloadLength) {
            0 -> if (delayedAckEnabled) {
                throw IOException("Delayed ACK stream missing OKAY payload for localId: ${localId.toString(16)}")
            } else {
                0
            }

            Int.SIZE_BYTES -> {
                if (!delayedAckEnabled) {
                    throw IOException("Unexpected OKAY payload for non-delayed-ack stream: ${message.payloadLength}")
                }
                Buffer().write(message.payload).readIntLe()
            }

            else -> throw IOException("Invalid OKAY payload size: ${message.payloadLength}")
        }
    }

    private fun nextMessage(command: Int): AdbMessage? {
        return try {
            messageQueue.take(localId, command)
        } catch (_: IOException) {
            close()
            return null
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        adbWriter.writeClose(localId, remoteId)
        messageQueue.stopListening(localId)
    }
}
