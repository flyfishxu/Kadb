package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.cert.HostKeySet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

internal actual suspend fun upgradeTls(
    channel: TransportChannel, host: String, port: Int, keys: HostKeySet, timeoutMs: Long
): TransportChannel {
    val socket = channel as PosixTransportChannel
    return try {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { socket.close() }
                try {
                    socket.startTls(keys.keyPairs, timeoutMs)
                    continuation.resume(socket, onCancellation = { _, value, _ -> value.close() })
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            }
        }
    } catch (error: Throwable) {
        socket.close()
        throw error
    }
}

internal actual fun mapInitialTlsReadError(error: Throwable): Throwable = error
