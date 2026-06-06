package com.flyfishxu.kadb.mdns.internal

import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceListener

internal class RealJmDnsBackend(
    private val jmDns: JmDNS
) : JmDnsBackend {
    private val listeners = mutableMapOf<ListenerKey, ListenerRegistration>()

    override fun addServiceListener(type: String, listener: JmDnsServiceEvents) {
        val serviceListener = JmDnsServiceListener(listener)
        val jmDnsType = type.toJmDnsServiceType()
        listeners[ListenerKey(type, listener)] = ListenerRegistration(jmDnsType, serviceListener)
        jmDns.addServiceListener(jmDnsType, serviceListener)
    }

    override fun removeServiceListener(type: String, listener: JmDnsServiceEvents) {
        val registration = listeners.remove(ListenerKey(type, listener)) ?: return
        jmDns.removeServiceListener(registration.type, registration.listener)
    }

    override fun requestServiceInfo(type: String, name: String) {
        jmDns.requestServiceInfo(type.toJmDnsServiceType(), name, true)
    }

    override fun close() {
        jmDns.close()
    }

    private data class ListenerKey(
        val type: String,
        val listener: JmDnsServiceEvents
    )

    private data class ListenerRegistration(
        val type: String,
        val listener: ServiceListener
    )
}

internal fun createDefaultJmDnsBackends(): List<JmDnsBackend> {
    val interfaceBackends = runCatching {
        eligibleInterfaceAddresses().map { address ->
            RealJmDnsBackend(JmDNS.create(address))
        }
    }.getOrElse { emptyList() }

    return interfaceBackends.ifEmpty {
        listOf(RealJmDnsBackend(JmDNS.create()))
    }
}

private fun eligibleInterfaceAddresses(): List<InetAddress> =
    NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { networkInterface ->
            runCatching {
                networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isVirtual
            }.getOrDefault(false)
        }
        .flatMap { networkInterface ->
            networkInterface.inetAddresses.asSequence()
        }
        .filter { address ->
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isMulticastAddress
        }
        .toList()

internal fun String.toJmDnsServiceType(): String =
    "${normalizeMdnsServiceType()}.local."
