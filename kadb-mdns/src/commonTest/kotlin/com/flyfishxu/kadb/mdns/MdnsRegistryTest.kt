package com.flyfishxu.kadb.mdns

import com.flyfishxu.kadb.mdns.internal.MdnsRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdnsRegistryTest {
    @Test
    fun groupsConnectAndPairingDevices() {
        val registry = MdnsRegistry(MdnsConfig())
        registry.starting()
        registry.started()

        registry.upsert(endpoint("phone", "192.168.1.8", 37123, MdnsServiceType.TLS_CONNECT))
        registry.upsert(endpoint("phone-pair", "192.168.1.8", 43011, MdnsServiceType.TLS_PAIRING))

        val state = registry.state.value
        assertEquals(MdnsStatus.STARTED, state.status)
        assertFalse(state.loading)
        assertEquals(listOf("phone"), state.connectDevices.map { it.name })
        assertEquals(listOf("phone-pair"), state.pairDevices.map { it.name })
    }

    @Test
    fun removesEndpointByNameAndServiceType() {
        val registry = MdnsRegistry(MdnsConfig())
        registry.starting()
        registry.started()
        registry.upsert(endpoint("phone", "192.168.1.8", 37123, MdnsServiceType.TLS_CONNECT))

        registry.remove(name = "phone", serviceType = MdnsServiceType.TLS_CONNECT)

        val state = registry.state.value
        assertTrue(state.connectDevices.isEmpty())
        assertTrue(state.loading)
    }

    @Test
    fun ignoresInvalidEndpointsAndDisabledServiceTypes() {
        val registry = MdnsRegistry(MdnsConfig(serviceTypes = setOf(MdnsServiceType.TLS_PAIRING)))
        registry.starting()
        registry.started()

        registry.upsert(endpoint("phone", "192.168.1.8", 37123, MdnsServiceType.TLS_CONNECT))
        registry.upsert(endpoint("", "192.168.1.8", 37123, MdnsServiceType.TLS_PAIRING))
        registry.upsert(endpoint("phone", "", 37123, MdnsServiceType.TLS_PAIRING))
        registry.upsert(endpoint("phone", "192.168.1.8", 0, MdnsServiceType.TLS_PAIRING))

        assertTrue(registry.state.value.allDevices.isEmpty())
    }

    private fun endpoint(name: String, host: String, port: Int, serviceType: MdnsServiceType) =
        MdnsEndpoint(name = name, host = host, port = port, serviceType = serviceType)
}
