package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.TcpKeepAlive
import java.nio.channels.AsynchronousSocketChannel
import java.net.SocketOption

internal fun configureTcpKeepAlive(channel: AsynchronousSocketChannel, policy: TcpKeepAlive) {
    try {
        val options = channel.supportedOptions().associateBy { it.name() }
        for ((name, value) in keepAliveValues(policy)) {
            @Suppress("UNCHECKED_CAST")
            val option = options[name] as? SocketOption<Int> ?: continue
            channel.setOption(option, value)
        }
    } catch (_: Exception) {
        // Extended options differ between operating systems/JDKs. Basic keepalive remains enabled.
    }
}

internal fun keepAliveValues(policy: TcpKeepAlive) = mapOf(
    "TCP_KEEPIDLE" to policy.idleSeconds,
    "TCP_KEEPINTERVAL" to policy.intervalSeconds,
    "TCP_KEEPCOUNT" to policy.probeCount,
)
