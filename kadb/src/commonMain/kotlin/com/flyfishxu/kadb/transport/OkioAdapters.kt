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

private const val DEFAULT_CHUNK_SIZE = 64 * 1024
private const val MAX_CHUNK_SIZE = 256 * 1024

/**
 * Okio bridge that reuses an internal scratch ByteBuffer to reduce allocations.
 */
internal fun TransportChannel.asOkioSource(readTimeoutMs: Long = 0L): Source = object : Source {
    private val timeout = Timeout().apply {
        if (readTimeoutMs > 0) timeout(readTimeoutMs, TimeUnit.MILLISECONDS)
    }
    private var scratch: ByteBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE)

    override fun timeout(): Timeout = timeout

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0L
        val toRead = byteCount.coerceAtMost(MAX_CHUNK_SIZE.toLong()).toInt()
        ensureCapacity(toRead)

        scratch.clear()
        scratch.limit(toRead)
        val read = runBlocking { read(scratch, readTimeoutMs, TimeUnit.MILLISECONDS) }
        if (read < 0) return -1

        scratch.flip()
        if (scratch.hasArray()) {
            val array = scratch.array()
            val offset = scratch.arrayOffset()
            sink.write(array, offset, read)
        } else {
            val bytes = ByteArray(read)
            scratch.get(bytes)
            sink.write(bytes)
        }
        return read.toLong()
    }

    override fun close() {
        // TransportChannel lifecycle managed externally.
    }

    private fun ensureCapacity(required: Int) {
        if (scratch.capacity() >= required) return
        val newCapacity = required.coerceAtMost(MAX_CHUNK_SIZE).coerceAtLeast(scratch.capacity() * 2)
        scratch = ByteBuffer.allocate(newCapacity)
    }
}

internal fun TransportChannel.asOkioSink(writeTimeoutMs: Long = 0L): Sink = object : Sink {
    private val timeout = Timeout().apply {
        if (writeTimeoutMs > 0) timeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
    }
    private var scratch: ByteBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE)

    override fun timeout(): Timeout = timeout

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val toWrite = remaining.coerceAtMost(MAX_CHUNK_SIZE.toLong()).toInt()
            ensureCapacity(toWrite)

            val array = scratch.array()
            val offset = scratch.arrayOffset()
            val copied = source.read(array, offset, toWrite)
            require(copied > 0) { "Unexpected EOF while reading from buffer" }

            scratch.clear()
            scratch.put(array, offset, copied)
            scratch.flip()
            runBlocking { writeExactly(scratch, writeTimeoutMs, TimeUnit.MILLISECONDS) }
            remaining -= copied
        }
    }

    override fun flush() {
        // writeExactly already flushes through the underlying channel.
    }

    override fun close() {
        // TransportChannel lifecycle managed externally.
    }

    private fun ensureCapacity(required: Int) {
        if (scratch.capacity() >= required) return
        val newCapacity = required.coerceAtMost(MAX_CHUNK_SIZE).coerceAtLeast(scratch.capacity() * 2)
        scratch = ByteBuffer.allocate(newCapacity)
    }
}
