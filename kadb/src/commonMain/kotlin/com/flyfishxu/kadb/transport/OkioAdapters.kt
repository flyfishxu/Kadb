package com.flyfishxu.kadb.transport

import okio.Sink
import okio.Source

internal expect fun TransportChannel.asOkioSource(readTimeoutMs: Long = 0L): Source
internal expect fun TransportChannel.asOkioSink(writeTimeoutMs: Long = 0L): Sink
