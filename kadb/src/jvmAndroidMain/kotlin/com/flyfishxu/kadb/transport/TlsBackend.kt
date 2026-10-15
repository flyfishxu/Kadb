package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.cert.HostKeySet
import com.flyfishxu.kadb.pair.SslUtils
import com.flyfishxu.kadb.tls.TlsErrorMapper
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLProtocolException

internal actual suspend fun upgradeTls(
    channel: TransportChannel, host: String, port: Int, keys: HostKeySet, timeoutMs: Long
): TransportChannel {
    val context = SslUtils.getSslContext(keys)
    val engine = SslUtils.newClientEngine(context, host, port)
    val tlsChannel = TlsNioChannel(channel, engine)
    try {
        tlsChannel.handshake(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (error: Throwable) {
        throw TlsErrorMapper.map(error)
    }
    return tlsChannel
}

internal actual fun mapInitialTlsReadError(error: Throwable): Throwable =
    if (error is SSLProtocolException) TlsErrorMapper.map(error) else error
