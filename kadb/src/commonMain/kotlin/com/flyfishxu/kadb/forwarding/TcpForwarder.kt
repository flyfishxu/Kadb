package com.flyfishxu.kadb.forwarding

import com.flyfishxu.kadb.Kadb

internal expect class TcpForwarder(kadb: Kadb, hostPort: Int, targetPort: Int) : AutoCloseable {
    fun start()
    override fun close()
}
