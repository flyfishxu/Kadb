package com.flyfishxu.kadb.mdns.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class JmDnsServiceTypeFormatterTest {
    @Test
    fun formatsCommonServiceTypeForJmDns() {
        assertEquals("_adb-tls-connect._tcp.local.", "_adb-tls-connect._tcp".toJmDnsServiceType())
    }

    @Test
    fun keepsAlreadyQualifiedServiceTypeStable() {
        assertEquals("_adb-tls-connect._tcp.local.", "_adb-tls-connect._tcp.local.".toJmDnsServiceType())
    }
}
