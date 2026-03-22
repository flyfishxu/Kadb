package com.flyfishxu.kadb.forwarding

import com.flyfishxu.kadb.Kadb

internal class LocalAbstractForwarder(
    private val kadb: Kadb,
    private val localName: String,
    private val remote: String,
) : BaseForwarder(
    kadb = kadb,
    remoteDestination = remote,
    endpointDescription = "localabstract:$localName",
    forwardingType = "localabstract",
) {

    override fun createServer(): ForwardingServer = LocalAbstractServerAdapter(localName)

    private class LocalAbstractServerAdapter(name: String) : ForwardingServer {
        private val delegate = LocalAbstractServer(name)

        override fun accept(): ForwardingClient = LocalAbstractClientAdapter(delegate.accept())

        override fun close() {
            delegate.close()
        }
    }

    private class LocalAbstractClientAdapter(
        private val delegate: LocalAbstractClient,
    ) : ForwardingClient {
        override val source = delegate.source
        override val sink = delegate.sink

        override fun close() {
            delegate.close()
        }
    }
}
