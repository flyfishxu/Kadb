package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.cert.HostKeySet

internal expect suspend fun upgradeTls(
    channel: TransportChannel, host: String, port: Int, keys: HostKeySet, timeoutMs: Long
): TransportChannel

internal expect fun mapInitialTlsReadError(error: Throwable): Throwable
