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

import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

internal fun TransportChannel.asOkioSource(readTimeoutMs: Long = 0L): Source = object : Source {
    private val timeout = Timeout().apply {
        if (readTimeoutMs > 0) timeout(readTimeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun timeout(): Timeout = timeout

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0L
        val toRead = byteCount.coerceAtMost(64 * 1024).toInt()
        val buf = ByteBuffer.allocate(toRead)
        val n = runBlocking { read(buf, readTimeoutMs, TimeUnit.MILLISECONDS) }
        if (n < 0) return -1
        buf.flip()
        val bytes = ByteArray(n)
        buf.get(bytes)
        sink.write(bytes)
        return n.toLong()
    }

    override fun close() { /* TransportChannel lifecycle managed elsewhere */ }
}

internal fun TransportChannel.asOkioSink(writeTimeoutMs: Long = 0L): Sink = object : Sink {
    private val timeout = Timeout().apply {
        if (writeTimeoutMs > 0) timeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun timeout(): Timeout = timeout

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val chunk = remaining.coerceAtMost(64 * 1024).toInt()
            val bytes = source.readByteArray(chunk.toLong())
            val buf = ByteBuffer.wrap(bytes)
            runBlocking { writeExactly(buf, writeTimeoutMs, TimeUnit.MILLISECONDS) }
            remaining -= chunk
        }
    }

    override fun flush() { /* writeExactly ensures data is pushed */ }

    override fun close() { /* TransportChannel lifecycle managed elsewhere */ }
}
