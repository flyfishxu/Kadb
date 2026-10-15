package com.flyfishxu.kadb.transport

import okio.Closeable

internal expect interface TransportChannel : Closeable {
    open val mayHaveStaleStreamPackets: Boolean
    val isOpen: Boolean
}
