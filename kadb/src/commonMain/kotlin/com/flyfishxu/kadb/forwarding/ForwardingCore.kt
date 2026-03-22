package com.flyfishxu.kadb.forwarding

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.debug.log
import com.flyfishxu.kadb.stream.AdbStream
import okio.BufferedSink
import okio.Source
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

internal interface ForwardingClient : AutoCloseable {
    val source: Source
    val sink: BufferedSink
    override fun close()
}

internal interface ForwardingServer : AutoCloseable {
    fun accept(): ForwardingClient
    override fun close()
}

internal interface ForwardingDuplex : AutoCloseable {
    val source: Source
    val sink: BufferedSink
    override fun close()
}

internal object StreamForwardStrategy {
    fun transfer(source: Source, sink: BufferedSink) {
        try {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (source.read(sink.buffer, 256) >= 0) {
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
            // Do nothing
        }
    }
}

internal abstract class BaseForwarder(
    private val kadb: Kadb,
    private val remoteDestination: String,
    private val endpointDescription: String,
    private val forwardingType: String,
    private val remoteChannelFactory: (String) -> ForwardingDuplex = { destination ->
        AdbForwardingDuplex(kadb.open(destination))
    },
) : AutoCloseable {

    private var serverThread: Thread? = null
    private var server: ForwardingServer? = null
    private var clientExecutor: ExecutorService? = null
    private val stateController = ForwarderStateController(endpointDescription)

    fun start() {
        stateController.prepareStart()

        clientExecutor = createClientExecutor()
        serverThread = thread {
            try {
                handleForwarding()
            } catch (_: SocketException) {
                // Do nothing
            } catch (e: IOException) {
                log { "could not start $forwardingType port forwarding: ${e.message}" }
            } catch (e: Throwable) {
                log { "could not start $forwardingType port forwarding: ${e.message}" }
            } finally {
                stateController.markStopped()
            }
        }

        stateController.awaitStarted()
    }

    private fun handleForwarding() {
        val serverRef = createServer()
        server = serverRef

        stateController.markStarted()

        while (!Thread.currentThread().isInterrupted) {
            val client = serverRef.accept()

            clientExecutor?.execute {
                try {
                    handleClient(client)
                } catch (e: Throwable) {
                    log { "Forwarder client handler failed: ${e.message}" }
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(client: ForwardingClient) {
        val remoteChannel = remoteChannelFactory(remoteDestination)

        val readerThread = thread {
            StreamForwardStrategy.transfer(client.source, remoteChannel.sink)
        }

        try {
            StreamForwardStrategy.transfer(remoteChannel.source, client.sink)
        } finally {
            runCatching { remoteChannel.close() }
            runCatching { client.close() }
            readerThread.interrupt()
        }
    }

    protected abstract fun createServer(): ForwardingServer

    override fun close() {
        val shouldStop = stateController.shouldStop()
        if (!shouldStop) return

        stateController.awaitStartedOrStopped()
        stateController.markStopping()

        server?.close()
        server = null
        serverThread?.interrupt()
        serverThread = null
        clientExecutor?.shutdown()
        clientExecutor?.awaitTermination(5, TimeUnit.SECONDS)
        clientExecutor = null

        stateController.awaitStopped()
    }

    private fun createClientExecutor(): ExecutorService {
        return ThreadPoolExecutor(
            1,
            MAX_CLIENT_THREADS,
            60,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(MAX_CLIENT_QUEUE_SIZE),
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
    }

    private companion object {
        const val MAX_CLIENT_THREADS = 32
        const val MAX_CLIENT_QUEUE_SIZE = 256
    }
}

private class AdbForwardingDuplex(
    private val stream: AdbStream,
) : ForwardingDuplex {
    override val source: Source = stream.source
    override val sink: BufferedSink = stream.sink

    override fun close() {
        stream.close()
    }
}

private class ForwarderStateController(
    private val endpointDescription: String,
) {
    @Volatile
    private var state: State = State.STOPPED
    private val lock = Any()
    private var startedSignal = CountDownLatch(0)
    private var stoppedSignal = CountDownLatch(0)

    fun prepareStart() {
        synchronized(lock) {
            check(state == State.STOPPED) { "Forwarder is already started at $endpointDescription" }
            startedSignal = CountDownLatch(1)
            stoppedSignal = CountDownLatch(1)
            state = State.STARTING
        }
    }

    fun shouldStop(): Boolean = synchronized(lock) {
        state != State.STOPPED && state != State.STOPPING
    }

    fun markStarted() {
        state = State.STARTED
        startedSignal.countDown()
    }

    fun markStopping() {
        state = State.STOPPING
    }

    fun markStopped() {
        state = State.STOPPED
        stoppedSignal.countDown()
    }

    fun awaitStarted() {
        awaitStateTransition(startedSignal, "start")
    }

    fun awaitStartedOrStopped() {
        if (state == State.STOPPED) return
        awaitStarted()
    }

    fun awaitStopped() {
        awaitStateTransition(stoppedSignal, "stop")
    }

    private enum class State {
        STARTING, STARTED, STOPPING, STOPPED
    }

    private fun awaitStateTransition(signal: CountDownLatch, action: String) {
        if (!signal.await(5, TimeUnit.SECONDS)) {
            throw TimeoutException("Timed out waiting for forwarder to $action")
        }
    }
}
