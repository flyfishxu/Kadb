package com.flyfishxu.kadb.mdns

import com.flyfishxu.kadb.mdns.internal.parseMdnsServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MdnsServiceTypeParserTest {
    @Test
    fun parsesAdbServiceTypesWithLocalSuffixAndCaseDifferences() {
        assertEquals(MdnsServiceType.ADB, parseMdnsServiceType("_adb._tcp"))
        assertEquals(MdnsServiceType.ADB, parseMdnsServiceType("_ADB._TCP."))
        assertEquals(MdnsServiceType.ADB, parseMdnsServiceType("_adb._tcp.local"))
        assertEquals(MdnsServiceType.TLS_CONNECT, parseMdnsServiceType("_adb-tls-connect._tcp.local."))
        assertEquals(MdnsServiceType.TLS_PAIRING, parseMdnsServiceType("_adb-tls-pairing._tcp"))
    }

    @Test
    fun returnsNullForUnknownServiceType() {
        assertNull(parseMdnsServiceType("_printer._tcp"))
        assertNull(parseMdnsServiceType(""))
        assertNull(parseMdnsServiceType(null))
    }
}
