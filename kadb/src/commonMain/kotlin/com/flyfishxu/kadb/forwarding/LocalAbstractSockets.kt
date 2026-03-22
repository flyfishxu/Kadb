package com.flyfishxu.kadb.forwarding

import okio.BufferedSink
import okio.Source

/**
 * Platform-specific local abstract unix domain socket listener.
 *
 * Mirrors adb's `localabstract:<name>` behavior for the local endpoint.
 */
internal expect class LocalAbstractServer(name: String) : AutoCloseable {
    fun accept(): LocalAbstractClient
    override fun close()
}

internal expect class LocalAbstractClient : AutoCloseable {
    val source: Source
    val sink: BufferedSink
    override fun close()
}
