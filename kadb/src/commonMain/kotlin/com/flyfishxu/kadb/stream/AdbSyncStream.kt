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

import okio.Buffer
import okio.Sink
import okio.Source
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.Throws

private const val LIST = "LIST"
private const val RECV = "RECV"
private const val SEND = "SEND"
private const val STAT = "STAT"
private const val DATA = "DATA"
private const val DONE = "DONE"
private const val OKAY = "OKAY"
private const val QUIT = "QUIT"
private const val FAIL = "FAIL"

internal val SYNC_IDS = setOf(LIST, RECV, SEND, STAT, DATA, DONE, OKAY, QUIT, FAIL)

private class Packet(val id: String, val arg: Int)

class AdbSyncStream(
    private val stream: AdbStream
) : AutoCloseable {

    private val buffer = Buffer()

    @Throws(IOException::class)
    fun send(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) {
        val remote = "$remotePath,$mode"
        val length = remote.toByteArray().size
        writePacket(SEND, length)

        stream.sink.apply {
            writeString(remote, StandardCharsets.UTF_8)
            flush()
        }

        buffer.clear()

        while (true) {
            val read = source.read(buffer, 64_000)
            if (read == -1L) break
            writePacket(DATA, read.toInt())
            val sent = stream.sink.writeAll(buffer)
            check(read == sent)
        }

        writePacket(DONE, (lastModifiedMs / 1000).toInt())

        stream.sink.flush()

        val packet = readPacket()
        when (packet.id) {
            OKAY -> return
            FAIL -> {
                val message = stream.source.readString(packet.arg.toLong(), StandardCharsets.UTF_8)
                throw IOException("Sync failed: $message")
            }

            else -> throw IOException("Unexpected sync packet id: ${packet.id}")
        }
    }

    @Throws(IOException::class)
    fun recv(sink: Sink, remotePath: String) {
        writePacket(RECV, remotePath.length)
        stream.sink.apply {
            writeString(remotePath, StandardCharsets.UTF_8)
            flush()
        }

        buffer.clear()

        while (true) {
            val packet = readPacket()
            when (packet.id) {
                DATA -> {
                    val chunkSize = packet.arg
                    stream.source.readFully(buffer, chunkSize.toLong())
                    buffer.readAll(sink)
                }

                DONE -> break
                FAIL -> {
                    val message = stream.source.readString(packet.arg.toLong(), StandardCharsets.UTF_8)
                    throw IOException("Sync failed: $message")
                }

                else -> throw IOException("Unexpected sync packet id: ${packet.id}")
            }
        }

        sink.flush()
    }

    private fun writePacket(id: String, arg: Int) {
        stream.sink.apply {
            writeString(id, StandardCharsets.UTF_8)
            writeIntLe(arg)
            flush()
        }
    }

    private fun readPacket(): Packet {
        val id = stream.source.readString(4, StandardCharsets.UTF_8)
        val arg = stream.source.readIntLe()
        return Packet(id, arg)
    }

    override fun close() {
        writePacket(QUIT, 0)
        stream.close()
    }
}