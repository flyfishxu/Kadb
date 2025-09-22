/*
 * Copyright (c) 2024 Flyfish-Xu
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
 */

package com.flyfishxu.kadb.transport

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

internal class TlsNioChannel(
    private val net: TransportChannel,
    private val engine: SSLEngine
) : TransportChannel {

    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)

    suspend fun handshake(timeout: Long, unit: TimeUnit) {
        netIn.clear()
        netIn.limit(0)
        appIn.clear()

        engine.beginHandshake()
        var status = engine.handshakeStatus

        loop@ while (true) {
            when (status) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(EMPTY.duplicate(), netOut)
                    status = result.handshakeStatus
                    when (result.status) {
                        SSLEngineResult.Status.OK -> {
                            netOut.flip()
                            while (netOut.hasRemaining()) {
                                net.writeExactly(netOut, timeout, unit)
                            }
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            netOut = enlarge(netOut, engine.session.packetBufferSize)
                        }
                        else -> throw SSLException("NEED_WRAP: ${result.status}")
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!netIn.hasRemaining()) {
                        netIn.compact()
                        val read = net.read(netIn, timeout, unit)
                        if (read < 0) throw SSLException("Channel closed during handshake")
                        netIn.flip()
                    }
                    val result = engine.unwrap(netIn, appIn)
                    status = result.handshakeStatus
                    when (result.status) {
                        SSLEngineResult.Status.OK -> Unit
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            if (netIn.limit() == netIn.capacity()) {
                                netIn = enlarge(netIn, engine.session.packetBufferSize)
                            }
                            netIn.compact()
                            val read = net.read(netIn, timeout, unit)
                            if (read < 0) throw SSLException("EOF during handshake")
                            netIn.flip()
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            appIn = enlarge(appIn, engine.session.applicationBufferSize)
                        }
                        else -> throw SSLException("NEED_UNWRAP: ${result.status}")
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                    status = engine.handshakeStatus
                }

                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> break@loop

                else -> {
                    if (status.name == "NEED_UNWRAP_AGAIN") {
                        status = SSLEngineResult.HandshakeStatus.NEED_UNWRAP
                    } else {
                        throw SSLException("Unsupported handshake status: $status")
                    }
                }
            }
        }
    }

    override suspend fun read(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
        appIn.flip()
        if (appIn.hasRemaining()) {
            val toCopy = minOf(dst.remaining(), appIn.remaining())
            val originalLimit = appIn.limit()
            appIn.limit(appIn.position() + toCopy)
            dst.put(appIn)
            appIn.limit(originalLimit)
            appIn.compact()
            return toCopy
        }
        appIn.compact()

        while (true) {
            if (!netIn.hasRemaining()) {
                netIn.clear()
                val read = net.read(netIn, timeout, unit)
                if (read < 0) return -1
                netIn.flip()
            }
            val result = engine.unwrap(netIn, appIn)
            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    if (appIn.position() > 0) {
                        appIn.flip()
                        val toCopy = minOf(dst.remaining(), appIn.remaining())
                        val originalLimit = appIn.limit()
                        appIn.limit(appIn.position() + toCopy)
                        dst.put(appIn)
                        appIn.limit(originalLimit)
                        appIn.compact()
                        return toCopy
                    }
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    appIn = enlarge(appIn, engine.session.applicationBufferSize)
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    netIn.compact()
                    val read = net.read(netIn, timeout, unit)
                    if (read < 0) return -1
                    netIn.flip()
                }
                SSLEngineResult.Status.CLOSED -> return -1
            }
        }
    }

    override suspend fun write(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
        netOut.clear()
        val result = engine.wrap(src, netOut)
        return when (result.status) {
            SSLEngineResult.Status.OK -> {
                netOut.flip()
                while (netOut.hasRemaining()) {
                    net.writeExactly(netOut, timeout, unit)
                }
                result.bytesConsumed()
            }
            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                netOut = enlarge(netOut, engine.session.packetBufferSize)
                write(src, timeout, unit)
            }
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> error("wrap BUFFER_UNDERFLOW should not happen")
            SSLEngineResult.Status.CLOSED -> -1
        }
    }

    override suspend fun readExactly(dst: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (dst.hasRemaining()) {
            val n = read(dst, timeout, unit)
            if (n < 0) throw java.io.EOFException("EOF while TLS readExactly")
        }
    }

    override suspend fun writeExactly(src: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (src.hasRemaining()) {
            val n = write(src, timeout, unit)
            if (n < 0) throw java.io.IOException("TLS write returned $n")
        }
    }

    override suspend fun shutdownInput() = net.shutdownInput()

    override suspend fun shutdownOutput() = net.shutdownOutput()

    override val localAddress get() = net.localAddress

    override val remoteAddress get() = net.remoteAddress

    override val isOpen get() = net.isOpen

    override fun close() = net.close()

    private fun enlarge(buffer: ByteBuffer, minimum: Int): ByteBuffer {
        val capacity = maxOf(buffer.capacity() * 2, minimum)
        val newBuffer = ByteBuffer.allocate(capacity)
        buffer.flip()
        newBuffer.put(buffer)
        return newBuffer
    }

    private companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
