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

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal class PlainBlockingChannel private constructor(
    private val socket: Socket
) : TransportChannel {

    companion object {
        fun connect(host: String, port: Int, connectTimeoutMs: Long): PlainBlockingChannel {
            val socket = Socket()
            socket.keepAlive = true
            socket.tcpNoDelay = true
            val timeout = connectTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            socket.connect(InetSocketAddress(host, port), timeout)
            return PlainBlockingChannel(socket)
        }
    }

    private val input get() = socket.getInputStream()
    private val output get() = socket.getOutputStream()

    override val localAddress: InetSocketAddress
        get() = socket.localSocketAddress as InetSocketAddress
    override val remoteAddress: InetSocketAddress
        get() = socket.remoteSocketAddress as InetSocketAddress

    override suspend fun read(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
        val oldTimeout = socket.soTimeout
        try {
            socket.soTimeout = if (timeout > 0) unit.toMillis(timeout).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
            val max = min(dst.remaining(), 64 * 1024)
            val buffer = ByteArray(max)
            val read = input.read(buffer)
            if (read <= 0) {
                return -1
            }
            dst.put(buffer, 0, read)
            return read
        } finally {
            socket.soTimeout = oldTimeout
        }
    }

    override suspend fun write(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
        val max = min(src.remaining(), 64 * 1024)
        val tmp = ByteArray(max)
        val originalLimit = src.limit()
        val originalPosition = src.position()
        src.limit(originalPosition + max)
        src.get(tmp)
        src.limit(originalLimit)
        output.write(tmp)
        return max
    }

    override suspend fun readExactly(dst: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (dst.hasRemaining()) {
            val read = read(dst, timeout, unit)
            if (read < 0) throw java.io.EOFException("EOF while readExactly")
        }
    }

    override suspend fun writeExactly(src: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (src.hasRemaining()) {
            val written = write(src, timeout, unit)
            if (written < 0) throw java.io.IOException("write returned $written")
        }
    }

    override suspend fun shutdownInput() {
        socket.shutdownInput()
    }

    override suspend fun shutdownOutput() {
        socket.shutdownOutput()
    }

    override val isOpen: Boolean
        get() = socket.isConnected && !socket.isClosed

    override fun close() {
        socket.close()
    }
}
