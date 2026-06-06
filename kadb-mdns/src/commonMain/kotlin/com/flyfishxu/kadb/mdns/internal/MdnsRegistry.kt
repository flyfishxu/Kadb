package com.flyfishxu.kadb.mdns.internal

import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.mdns.MdnsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class MdnsRegistry(
    private val config: MdnsConfig
) {
    private val endpoints = linkedMapOf<MdnsServiceKey, MdnsEndpoint>()
    private val mutableState = MutableStateFlow(MdnsDiscoveryState())

    val state: StateFlow<MdnsDiscoveryState> = mutableState

    fun starting() {
        endpoints.clear()
        mutableState.value = MdnsDiscoveryState(status = MdnsStatus.STARTING, loading = true)
    }

    fun started() {
        emit(status = MdnsStatus.STARTED)
    }

    fun failed() {
        mutableState.value = mutableState.value.copy(status = MdnsStatus.FAILED, loading = false)
    }

    fun stopped() {
        endpoints.clear()
        mutableState.value = MdnsDiscoveryState(status = MdnsStatus.STOPPED, loading = false)
    }

    fun upsert(endpoint: MdnsEndpoint) {
        if (endpoint.serviceType !in config.serviceTypes) return
        if (endpoint.name.isBlank()) return
        if (endpoint.host.isBlank()) return
        if (endpoint.port !in 1..65535) return

        endpoints[MdnsServiceKey(endpoint.name, endpoint.serviceType)] = endpoint
        emit(status = currentActiveStatus())
    }

    fun remove(name: String, serviceType: MdnsServiceType) {
        endpoints.remove(MdnsServiceKey(name, serviceType))
        emit(status = currentActiveStatus())
    }

    private fun currentActiveStatus(): MdnsStatus =
        if (mutableState.value.status == MdnsStatus.STOPPED) MdnsStatus.STOPPED else MdnsStatus.STARTED

    private fun emit(status: MdnsStatus) {
        val values = endpoints.values.sortedWith(
            compareBy(MdnsEndpoint::name, MdnsEndpoint::host, MdnsEndpoint::port)
        )
        val connectDevices = values.filter {
            it.serviceType == MdnsServiceType.ADB || it.serviceType == MdnsServiceType.TLS_CONNECT
        }
        val pairDevices = values.filter { it.serviceType == MdnsServiceType.TLS_PAIRING }
        mutableState.value = MdnsDiscoveryState(
            status = status,
            loading = status != MdnsStatus.STOPPED && status != MdnsStatus.FAILED && values.isEmpty(),
            connectDevices = connectDevices,
            pairDevices = pairDevices
        )
    }
}
