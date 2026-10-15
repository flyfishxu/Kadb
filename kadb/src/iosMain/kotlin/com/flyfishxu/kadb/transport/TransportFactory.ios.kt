package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.TcpKeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal actual object TransportFactory {
    actual suspend fun connect(host: String, port: Int, connectTimeoutMs: Long, tcpKeepAlive: TcpKeepAlive?): TransportChannel {
        var channel: PosixTransportChannel? = null
        return try {
            withContext(Dispatchers.IO) {
                PosixTransportChannel.connect(host, port, connectTimeoutMs, tcpKeepAlive).also {
                    channel = it
                    ensureActive()
                }
            }
        } catch (error: Throwable) {
            channel?.close()
            throw error
        }
    }
}
