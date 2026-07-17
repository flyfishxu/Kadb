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
 *
 */

package com.flyfishxu.kadb.forwarding

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.debug.log
import okio.*
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

internal class TcpForwarder(
    private val kadb: Kadb,
    private val hostPort: Int,
    private val targetPort: Int,
) : AutoCloseable {
    private companion object {
        const val FORWARD_BUFFER_SIZE = 64 * 1024L
    }

    @Volatile
    private var state: State = State.STOPPED
    private var serverThread: Thread? = null
    private var server: ServerSocket? = null
    private var clientExecutor: ExecutorService? = null
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    private val activeStreams = ConcurrentHashMap.newKeySet<com.flyfishxu.kadb.stream.AdbStream>()
    @Volatile
    private var startupError: Throwable? = null

    fun start() {
        check(state == State.STOPPED) { "Forwarder is already started at port $hostPort" }

        moveToState(State.STARTING)
        startupError = null

        clientExecutor = Executors.newCachedThreadPool()
        serverThread = thread {
            try {
                handleForwarding()
            } catch (e: SocketException) {
                if (state == State.STARTING) startupError = e
            } catch (e: IOException) {
                if (state == State.STARTING) startupError = e
                log { "could not start TCP port forwarding: ${e.message}" }
            } finally {
                moveToState(State.STOPPED)
            }
        }

        waitFor(10, 5000) {
            state == State.STARTED || state == State.STOPPED
        }
        if (state != State.STARTED) {
            clientExecutor?.shutdownNow()
            clientExecutor = null
            serverThread = null
            throw IOException("Could not start TCP port forwarding on port $hostPort", startupError)
        }
    }

    private fun handleForwarding() {
        val serverRef = ServerSocket(hostPort)
        server = serverRef

        moveToState(State.STARTED)

        while (!Thread.interrupted()) {
            val client = serverRef.accept()
            activeClients += client

            clientExecutor?.execute {
                var adbStream: com.flyfishxu.kadb.stream.AdbStream? = null
                var readerThread: Thread? = null
                try {
                    adbStream = kadb.open("tcp:$targetPort")
                    activeStreams += adbStream
                    val stream = adbStream
                    readerThread = thread {
                        try {
                            forward(client.getInputStream().source(), stream.sink)
                        } finally {
                            stream.close()
                        }
                    }
                    forward(
                        stream.source, client.sink().buffer()
                    )
                } finally {
                    adbStream?.let(activeStreams::remove)
                    adbStream?.close()
                    activeClients.remove(client)
                    client.close()
                    readerThread?.interrupt()
                }
            }
        }
    }

    override fun close() {
        if (state == State.STOPPED || state == State.STOPPING) {
            return
        }

        // Make sure that we are not stopping the server while it is in a transient state
        // to avoid surprises
        waitFor(10, 5000) {
            state != State.STARTING
        }
        if (state == State.STOPPED) return

        moveToState(State.STOPPING)

        server?.close()
        server = null
        activeClients.toList().forEach { runCatching { it.close() } }
        activeStreams.toList().forEach { runCatching { it.close() } }
        serverThread?.interrupt()
        serverThread = null
        clientExecutor?.shutdownNow()
        clientExecutor?.awaitTermination(5, TimeUnit.SECONDS)
        clientExecutor = null
        activeClients.clear()
        activeStreams.clear()

        waitFor(10, 5000) {
            state == State.STOPPED
        }
    }

    private fun forward(source: Source, sink: BufferedSink) {
        try {
            while (!Thread.interrupted()) {
                try {
                    if (source.read(sink.buffer, FORWARD_BUFFER_SIZE) >= 0) {
                        sink.flush()
                    } else {
                        return
                    }
                } catch (_: IOException) {
                    return
                }
            }
        } catch (_: InterruptedException) {
            // Do nothing
        } catch (_: InterruptedIOException) {
            // do nothing
        }
    }

    private fun moveToState(state: State) {
        this.state = state
    }

    private enum class State {
        STARTING, STARTED, STOPPING, STOPPED
    }

    private fun waitFor(intervalMs: Int, timeoutMs: Int, test: () -> Boolean) {
        val start = System.currentTimeMillis()
        var lastCheck = start
        while (!test()) {
            val now = System.currentTimeMillis()
            val timeSinceStart = now - start
            val timeSinceLastCheck = now - lastCheck
            if (timeoutMs in 0..timeSinceStart) {
                throw TimeoutException()
            }
            val sleepTime = intervalMs - timeSinceLastCheck
            if (sleepTime > 0) {
                Thread.sleep(sleepTime)
            }
            lastCheck = System.currentTimeMillis()
        }
    }

}
