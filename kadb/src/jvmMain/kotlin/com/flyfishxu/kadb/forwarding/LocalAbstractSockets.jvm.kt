package com.flyfishxu.kadb.forwarding

import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import org.newsclub.net.unix.AFUNIXServerSocket
import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress

internal actual class LocalAbstractServer actual constructor(name: String) : AutoCloseable {
    private val server: AFUNIXServerSocket = AFUNIXServerSocket.newInstance()

    init {
        val address = AFUNIXSocketAddress.inAbstractNamespace(name)
        server.bind(address)
    }

    actual fun accept(): LocalAbstractClient {
        val socket = server.accept() as AFUNIXSocket
        return LocalAbstractClient(socket)
    }

    actual override fun close() {
        runCatching { server.close() }
    }
}

internal actual class LocalAbstractClient internal constructor(
    private val socket: AFUNIXSocket
) : AutoCloseable {

    actual val source: Source = socket.inputStream.source()
    actual val sink: BufferedSink = socket.outputStream.sink().buffer()

    actual override fun close() {
        runCatching { socket.close() }
    }
}
