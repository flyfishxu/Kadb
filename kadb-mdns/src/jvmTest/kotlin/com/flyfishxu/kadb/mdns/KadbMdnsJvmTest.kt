package com.flyfishxu.kadb.mdns

import com.flyfishxu.kadb.mdns.internal.FakeJmDnsBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KadbMdnsJvmTest {
    @Test
    fun startsListenersAndPublishesResolvedServices() {
        val backend = FakeJmDnsBackend()
        val mdns = KadbMdnsJvm(config = MdnsConfig(), backendFactory = { listOf(backend) })

        mdns.start()
        backend.emitAdded("_adb-tls-connect._tcp.local.", "Pixel", "192.168.1.20", 37123)

        val state = mdns.state.value
        assertEquals(MdnsStatus.STARTED, state.status)
        assertEquals("Pixel", state.connectDevices.single().name)
        assertEquals("192.168.1.20", state.connectDevices.single().host)
        assertEquals(37123, state.connectDevices.single().port)
    }

    @Test
    fun removesLostServices() {
        val backend = FakeJmDnsBackend()
        val mdns = KadbMdnsJvm(config = MdnsConfig(), backendFactory = { listOf(backend) })

        mdns.start()
        backend.emitAdded("_adb-tls-pairing._tcp.local.", "Pixel Pair", "192.168.1.20", 43011)
        backend.emitRemoved("_adb-tls-pairing._tcp.local.", "Pixel Pair")

        assertEquals(emptyList(), mdns.state.value.pairDevices)
    }

    @Test
    fun stopRemovesListenersClosesBackendsAndClearsState() {
        val backend = FakeJmDnsBackend()
        val mdns = KadbMdnsJvm(config = MdnsConfig(), backendFactory = { listOf(backend) })

        mdns.start()
        mdns.stop()

        assertEquals(MdnsStatus.STOPPED, mdns.state.value.status)
        assertTrue(mdns.state.value.allDevices.isEmpty())
        assertEquals(MdnsServiceType.entries.map { it.dnsType }.toSet(), backend.removedListenerTypes)
        assertTrue(backend.closed)
    }
}
