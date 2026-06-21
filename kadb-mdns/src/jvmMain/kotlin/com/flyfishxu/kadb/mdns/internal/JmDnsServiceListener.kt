package com.flyfishxu.kadb.mdns.internal

import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

internal class JmDnsServiceListener(
    private val events: JmDnsServiceEvents
) : ServiceListener {
    override fun serviceAdded(event: ServiceEvent) {
        events.serviceAdded(event.type, event.name)
    }

    override fun serviceRemoved(event: ServiceEvent) {
        events.serviceRemoved(event.type, event.name)
    }

    override fun serviceResolved(event: ServiceEvent) {
        val info = event.info ?: return
        events.serviceResolved(
            type = event.type,
            name = event.name.ifBlank { info.name },
            hostAddresses = info.hostAddresses,
            port = info.port
        )
    }
}
