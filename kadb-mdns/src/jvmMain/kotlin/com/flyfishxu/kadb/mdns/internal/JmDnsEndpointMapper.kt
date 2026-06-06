package com.flyfishxu.kadb.mdns.internal

import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsEndpoint

internal fun mapJmDnsEndpoint(
    type: String,
    name: String,
    hostAddresses: Array<String>,
    port: Int,
    config: MdnsConfig
): MdnsEndpoint? {
    val serviceType = parseMdnsServiceType(type) ?: return null
    if (serviceType !in config.serviceTypes) return null
    val safeName = name.trim().takeIf { it.isNotBlank() } ?: return null
    val host = selectHostAddress(hostAddresses, config.preferIpv4) ?: return null
    val safePort = port.takeIf { it in 1..65535 } ?: return null
    return MdnsEndpoint(
        name = safeName,
        host = host,
        port = safePort,
        serviceType = serviceType
    )
}

private fun selectHostAddress(addresses: Array<String>, preferIpv4: Boolean): String? {
    val candidates = addresses.mapNotNull { address ->
        address.trim().takeIf { it.isNotBlank() }
    }
    return if (preferIpv4) {
        candidates.firstOrNull { ':' !in it } ?: candidates.firstOrNull()
    } else {
        candidates.firstOrNull()
    }
}
