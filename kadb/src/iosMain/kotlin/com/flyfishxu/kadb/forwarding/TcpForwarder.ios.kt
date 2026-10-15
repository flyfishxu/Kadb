@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flyfishxu.kadb.forwarding

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.concurrent.BlockingLock
import com.flyfishxu.kadb.concurrent.startKadbThread
import com.flyfishxu.kadb.concurrent.withLock
import com.flyfishxu.kadb.debug.log
import com.flyfishxu.kadb.stream.AdbStream
import com.flyfishxu.kadb.transport.PosixTransportChannel
import com.flyfishxu.kadb.transport.asOkioSink
import com.flyfishxu.kadb.transport.asOkioSource
import kotlinx.cinterop.*
import okio.BufferedSink
import okio.IOException
import okio.Source
import okio.buffer
import platform.CoreFoundation.CFSwapInt16HostToBig
import platform.posix.*

internal actual class TcpForwarder actual constructor(
    private val kadb: Kadb,
    private val hostPort: Int,
    private val targetPort: Int,
) : AutoCloseable {
    private val lock = BlockingLock()
    private val stopped = lock.newCondition()
    private var listener: Listener? = null

    actual fun start() {
        require(hostPort in 0..65535) { "Invalid host port: $hostPort" }
        require(targetPort in 1..65535) { "Invalid target port: $targetPort" }
        lock.withLock {
            if (listener?.closed == true) listener = null
            check(listener == null) { "Forwarder is already started at port $hostPort" }
            val created = Listener(createListener(hostPort))
            listener = created
            try {
                startKadbThread("kadb-forward-accept") { acceptClients(created) }
            } catch (failure: Throwable) {
                listener = null
                platform.posix.close(created.fd)
                throw failure
            }
        }
    }

    actual override fun close() {
        val current: Listener
        val clients: List<Client>
        lock.withLock {
            current = listener ?: return
            current.active = false
            clients = current.clients.toList()
            // Wake poll/accept, but let the accepting thread close its own descriptor.
            // Closing here could let an in-flight accept use a newly reused descriptor.
            if (!current.closed) shutdown(current.fd, SHUT_RDWR)
        }
        clients.forEach { it.close() }
        lock.withLock {
            while (!current.closed) stopped.await()
            if (listener === current) listener = null
        }
    }

    private fun acceptClients(current: Listener) {
        try {
            memScoped {
                val pollFd = alloc<pollfd> {
                    fd = current.fd
                    events = POLLIN.toShort()
                }
                while (lock.withLock { current.active }) {
                    val ready = poll(pollFd.ptr, 1u, 200)
                    if (ready < 0) {
                        if (errno == EINTR) continue
                        throw socketFailure("poll")
                    }
                    if (ready == 0 || !lock.withLock { current.active }) continue
                    val clientFd = accept(current.fd, null, null)
                    if (clientFd < 0) {
                        if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue
                        if (!lock.withLock { current.active }) break
                        throw socketFailure("accept")
                    }
                    // The channel takes ownership, including its initialization-failure path.
                    val client = Client(PosixTransportChannel(clientFd))
                    val accepted = lock.withLock {
                        if (current.active) {
                            current.clients.add(client)
                            true
                        } else false
                    }
                    if (!accepted) {
                        client.close()
                        continue
                    }
                    try {
                        startKadbThread("kadb-forward-client") { forwardClient(current, client) }
                    } catch (failure: Throwable) {
                        lock.withLock { current.clients.remove(client) }
                        client.close()
                        throw failure
                    }
                }
            }
        } catch (failure: Exception) {
            if (lock.withLock { current.active }) {
                log { "could not continue TCP port forwarding: ${failure.message}" }
            }
        } finally {
            val clients = lock.withLock {
                platform.posix.close(current.fd)
                current.active = false
                current.closed = true
                stopped.signalAll()
                current.clients.toList()
            }
            clients.forEach { it.close() }
        }
    }

    private fun forwardClient(current: Listener, client: Client) {
        try {
            val stream = kadb.open("tcp:$targetPort")
            if (!client.attach(stream)) return
            startKadbThread("kadb-forward-upload") {
                try {
                    forward(client.channel.asOkioSource(), stream.sink)
                } catch (failure: Exception) {
                    log { "TCP forwarding upload stopped: ${failure.message}" }
                } finally {
                    client.close()
                }
            }
            forward(stream.source, client.channel.asOkioSink().buffer())
        } catch (failure: Exception) {
            log { "TCP forwarding client stopped: ${failure.message}" }
        } finally {
            client.close()
            lock.withLock { current.clients.remove(client) }
        }
    }

    private fun forward(source: Source, sink: BufferedSink) {
        while (source.read(sink.buffer, 8192) != -1L) sink.flush()
    }

    private class Listener(val fd: Int) {
        var active = true
        var closed = false
        val clients = mutableSetOf<Client>()
    }

    private class Client(val channel: PosixTransportChannel) : AutoCloseable {
        private val lock = BlockingLock()
        private var stream: AdbStream? = null
        private var closed = false

        fun attach(value: AdbStream): Boolean {
            val accepted = lock.withLock {
                if (closed) false else {
                    stream = value
                    true
                }
            }
            if (!accepted) value.close()
            return accepted
        }

        override fun close() {
            val current = lock.withLock {
                if (closed) return
                closed = true
                stream.also { stream = null }
            }
            runCatching { channel.close() }
                .onFailure { log { "TCP forwarding socket close failed: ${it.message}" } }
            runCatching { current?.close() }
                .onFailure { log { "TCP forwarding stream close failed: ${it.message}" } }
        }
    }
}

private fun createListener(port: Int): Int = memScoped {
    // A dual-stack IPv6 listener matches JVM ServerSocket's wildcard behavior.
    var family = AF_INET6
    var fd = socket(family, SOCK_STREAM, 0)
    if (fd < 0) {
        family = AF_INET
        fd = socket(family, SOCK_STREAM, 0)
    }
    if (fd < 0) throw socketFailure("socket")
    try {
        val option = alloc<IntVar> { value = 1 }
        if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, option.ptr, sizeOf<IntVar>().toUInt()) != 0) {
            throw socketFailure("setsockopt(SO_REUSEADDR)")
        }
        val bound = if (family == AF_INET6) {
            option.value = 0
            if (setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, option.ptr, sizeOf<IntVar>().toUInt()) != 0) {
                throw socketFailure("setsockopt(IPV6_V6ONLY)")
            }
            val address = alloc<sockaddr_in6> {
                memset(ptr, 0, sizeOf<sockaddr_in6>().toULong())
                sin6_len = sizeOf<sockaddr_in6>().toUByte()
                sin6_family = AF_INET6.toUByte()
                sin6_port = CFSwapInt16HostToBig(port.toUShort())
            }
            bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in6>().toUInt())
        } else {
            val address = alloc<sockaddr_in> {
                memset(ptr, 0, sizeOf<sockaddr_in>().toULong())
                sin_len = sizeOf<sockaddr_in>().toUByte()
                sin_family = AF_INET.toUByte()
                sin_port = CFSwapInt16HostToBig(port.toUShort())
                sin_addr.s_addr = INADDR_ANY
            }
            bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
        }
        if (bound != 0) throw socketFailure("bind")
        if (listen(fd, SOMAXCONN) != 0) throw socketFailure("listen")
        if (fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) or O_NONBLOCK) == -1) {
            throw socketFailure("fcntl")
        }
        fd
    } catch (failure: Throwable) {
        platform.posix.close(fd)
        throw failure
    }
}

private fun socketFailure(operation: String): IOException =
    IOException("$operation failed: ${strerror(errno)?.toKString() ?: errno.toString()}")
