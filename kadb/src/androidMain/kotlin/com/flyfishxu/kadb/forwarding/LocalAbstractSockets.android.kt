package com.flyfishxu.kadb.forwarding

import android.net.LocalServerSocket
import android.net.LocalSocket
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.sink
import okio.source

internal actual class LocalAbstractServer actual constructor(name: String) : AutoCloseable {
    private val server = LocalServerSocket(name)

    actual fun accept(): LocalAbstractClient = LocalAbstractClient(server.accept())

    actual override fun close() {
        runCatching { server.close() }
    }
}

internal actual class LocalAbstractClient internal constructor(
    private val socket: LocalSocket
) : AutoCloseable {

    actual val source: Source = socket.inputStream.source()
    actual val sink: BufferedSink = socket.outputStream.sink().buffer()

    actual override fun close() {
        runCatching { socket.close() }
    }
}
