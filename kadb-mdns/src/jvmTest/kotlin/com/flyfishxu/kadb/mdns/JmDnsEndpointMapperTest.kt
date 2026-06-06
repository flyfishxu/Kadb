package com.flyfishxu.kadb.mdns

import com.flyfishxu.kadb.mdns.internal.mapJmDnsEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JmDnsEndpointMapperTest {
    @Test
    fun mapsValidResolvedService() {
        val endpoint = mapJmDnsEndpoint(
            type = "_adb-tls-connect._tcp.local.",
            name = "Pixel",
            hostAddresses = arrayOf("fe80::1", "192.168.1.20"),
            port = 37123,
            config = MdnsConfig(preferIpv4 = true)
        )

        assertEquals(
            MdnsEndpoint(
                name = "Pixel",
                host = "192.168.1.20",
                port = 37123,
                serviceType = MdnsServiceType.TLS_CONNECT
            ),
            endpoint
        )
    }

    @Test
    fun rejectsUnknownTypeBlankHostAndInvalidPort() {
        assertNull(
            mapJmDnsEndpoint(
                type = "_printer._tcp.local.",
                name = "Printer",
                hostAddresses = arrayOf("192.168.1.2"),
                port = 1234,
                config = MdnsConfig()
            )
        )
        assertNull(
            mapJmDnsEndpoint(
                type = "_adb-tls-connect._tcp.local.",
                name = "Pixel",
                hostAddresses = emptyArray(),
                port = 37123,
                config = MdnsConfig()
            )
        )
        assertNull(
            mapJmDnsEndpoint(
                type = "_adb-tls-connect._tcp.local.",
                name = "Pixel",
                hostAddresses = arrayOf("192.168.1.20"),
                port = 0,
                config = MdnsConfig()
            )
        )
    }
}
