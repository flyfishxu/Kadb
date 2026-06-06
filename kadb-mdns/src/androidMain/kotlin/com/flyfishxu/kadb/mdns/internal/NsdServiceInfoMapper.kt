package com.flyfishxu.kadb.mdns.internal

import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import java.net.Inet4Address
import java.net.InetAddress

internal fun NsdServiceInfo.toMdnsEndpoint(
    config: MdnsConfig,
    fallbackServiceType: MdnsServiceType
): MdnsEndpoint? {
    val key = toMdnsServiceKey(fallbackServiceType) ?: return null
    val hostAddress = resolveHostAddress(config.preferIpv4) ?: return null
    val safePort = port.takeIf { it in 1..65535 } ?: return null
    return MdnsEndpoint(
        name = key.name,
        host = hostAddress,
        port = safePort,
        serviceType = key.serviceType
    )
}

internal fun NsdServiceInfo.toMdnsServiceKey(
    fallbackServiceType: MdnsServiceType
): MdnsServiceKey? {
    val name = serviceName?.trim().orEmpty()
    if (name.isBlank()) return null
    val parsedType = parseMdnsServiceType(serviceType) ?: fallbackServiceType
    return MdnsServiceKey(name = name, serviceType = parsedType)
}

private fun NsdServiceInfo.resolveHostAddress(preferIpv4: Boolean): String? {
    val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        selectAddress(hostAddresses, preferIpv4)
    } else {
        @Suppress("DEPRECATION")
        host
    }
    return address?.hostAddress?.takeIf { it.isNotBlank() }
}

private fun selectAddress(addresses: List<InetAddress>, preferIpv4: Boolean): InetAddress? =
    if (preferIpv4) {
        addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
    } else {
        addresses.firstOrNull()
    }
