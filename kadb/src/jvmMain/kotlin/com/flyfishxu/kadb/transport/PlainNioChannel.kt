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

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.net.StandardSocketOptions
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PlainNioChannel private constructor(
    private val channel: AsynchronousSocketChannel
) : TransportChannel {

    companion object {
        suspend fun connect(host: String, port: Int, timeout: Long, unit: TimeUnit): PlainNioChannel {
            val ch = AsynchronousSocketChannel.open()
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true)
            ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
            try {
                suspend fun doConnect() {
                    suspendCancellableCoroutine<Unit> { cont ->
                        cont.invokeOnCancellation {
                            try {
                                ch.close()
                            } catch (_: Throwable) {
                            }
                        }
                        ch.connect(InetSocketAddress(host, port), cont, object : CompletionHandler<Void, CancellableContinuation<Unit>> {
                            override fun completed(result: Void?, c: CancellableContinuation<Unit>) {
                                c.resume(Unit)
                            }

                            override fun failed(exc: Throwable, c: CancellableContinuation<Unit>) {
                                c.resumeWithException(exc)
                            }
                        })
                    }
                }
                if (timeout > 0) {
                    withTimeout(unit.toMillis(timeout)) { doConnect() }
                } else {
                    doConnect()
                }
            } catch (t: Throwable) {
                try {
                    ch.close()
                } catch (_: Throwable) {
                }
                throw t
            }
            return PlainNioChannel(ch)
        }
    }

    override val localAddress: InetSocketAddress
        get() = channel.localAddress as InetSocketAddress
    override val remoteAddress: InetSocketAddress
        get() = channel.remoteAddress as InetSocketAddress

    private suspend fun aRead(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int =
        suspendCancellableCoroutine { cont: CancellableContinuation<Int> ->
            cont.invokeOnCancellation {
                try {
                    channel.close()
                } catch (_: Throwable) {
                }
            }
            val handler = object : CompletionHandler<Int, CancellableContinuation<Int>> {
                override fun completed(result: Int, c: CancellableContinuation<Int>) {
                    c.resume(result)
                }

                override fun failed(exc: Throwable, c: CancellableContinuation<Int>) {
                    c.resumeWithException(exc)
                }
            }
            if (timeout > 0) {
                channel.read(dst, timeout, unit, cont, handler)
            } else {
                channel.read(dst, cont, handler)
            }
        }

    private suspend fun aWrite(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int =
        suspendCancellableCoroutine { cont: CancellableContinuation<Int> ->
            cont.invokeOnCancellation {
                try {
                    channel.close()
                } catch (_: Throwable) {
                }
            }
            val handler = object : CompletionHandler<Int, CancellableContinuation<Int>> {
                override fun completed(result: Int, c: CancellableContinuation<Int>) {
                    c.resume(result)
                }

                override fun failed(exc: Throwable, c: CancellableContinuation<Int>) {
                    c.resumeWithException(exc)
                }
            }
            if (timeout > 0) {
                channel.write(src, timeout, unit, cont, handler)
            } else {
                channel.write(src, cont, handler)
            }
        }

    override suspend fun read(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int =
        aRead(dst, timeout, unit)

    override suspend fun write(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int =
        aWrite(src, timeout, unit)

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
        channel.shutdownInput()
    }

    override suspend fun shutdownOutput() {
        channel.shutdownOutput()
    }

    override val isOpen: Boolean
        get() = channel.isOpen

    override fun close() {
        channel.close()
    }
}
