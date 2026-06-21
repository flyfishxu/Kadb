package com.flyfishxu.kadb.mdns

import com.flyfishxu.kadb.mdns.internal.JmDnsBackend
import com.flyfishxu.kadb.mdns.internal.JmDnsServiceEvents
import com.flyfishxu.kadb.mdns.internal.MdnsRegistry
import com.flyfishxu.kadb.mdns.internal.createDefaultJmDnsBackends
import com.flyfishxu.kadb.mdns.internal.mapJmDnsEndpoint
import kotlinx.coroutines.flow.StateFlow

class KadbMdnsJvm(
    private val config: MdnsConfig = MdnsConfig()
) : KadbMdns {
    private constructor(
        config: MdnsConfig,
        backendFactory: () -> List<JmDnsBackend>,
        internalMarker: Unit
    ) : this(config) {
        this.backendFactory = backendFactory
    }

    internal constructor(
        config: MdnsConfig,
        backendFactory: () -> List<JmDnsBackend>
    ) : this(config, backendFactory, Unit)

    private val registry = MdnsRegistry(config)
    private val lock = Any()
    private var backendFactory: () -> List<JmDnsBackend> = ::createDefaultJmDnsBackends
    private var backends = emptyList<JmDnsBackend>()
    private var registrations = emptyList<Registration>()
    private var started = false

    override val state: StateFlow<MdnsDiscoveryState> = registry.state

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            registry.starting()
        }

        val createdBackends = runCatching { backendFactory() }
            .getOrElse {
                synchronized(lock) {
                    started = false
                    registry.failed()
                }
                return
            }

        if (createdBackends.isEmpty()) {
            synchronized(lock) {
                started = false
                registry.failed()
            }
            return
        }

        val newRegistrations = buildList {
            createdBackends.forEach { backend ->
                config.serviceTypes.forEach { serviceType ->
                    val listener = JvmMdnsServiceEvents(backend)
                    backend.addServiceListener(serviceType.dnsType, listener)
                    add(Registration(backend, serviceType.dnsType, listener))
                }
            }
        }

        synchronized(lock) {
            if (!started) {
                newRegistrations.forEach { it.backend.removeServiceListener(it.type, it.listener) }
                createdBackends.forEach { it.close() }
                return
            }
            backends = createdBackends
            registrations = newRegistrations
            registry.started()
        }
    }

    override fun stop() {
        val currentRegistrations: List<Registration>
        val currentBackends: List<JmDnsBackend>
        synchronized(lock) {
            if (!started && backends.isEmpty()) return
            started = false
            currentRegistrations = registrations
            currentBackends = backends
            registrations = emptyList()
            backends = emptyList()
        }

        currentRegistrations.forEach { registration ->
            runCatching {
                registration.backend.removeServiceListener(registration.type, registration.listener)
            }
        }
        currentBackends.forEach { backend ->
            runCatching {
                backend.close()
            }
        }
        synchronized(lock) {
            registry.stopped()
        }
    }

    private inner class JvmMdnsServiceEvents(
        private val backend: JmDnsBackend
    ) : JmDnsServiceEvents {
        override fun serviceAdded(type: String, name: String) {
            backend.requestServiceInfo(type, name)
        }

        override fun serviceResolved(type: String, name: String, hostAddresses: Array<String>, port: Int) {
            val endpoint = mapJmDnsEndpoint(type, name, hostAddresses, port, config) ?: return
            synchronized(lock) {
                if (started) {
                    registry.upsert(endpoint)
                }
            }
        }

        override fun serviceRemoved(type: String, name: String) {
            val serviceType = com.flyfishxu.kadb.mdns.internal.parseMdnsServiceType(type) ?: return
            synchronized(lock) {
                if (started) {
                    registry.remove(name, serviceType)
                }
            }
        }
    }

    private data class Registration(
        val backend: JmDnsBackend,
        val type: String,
        val listener: JmDnsServiceEvents
    )
}
