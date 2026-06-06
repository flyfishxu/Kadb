package com.flyfishxu.kadb.mdns.internal

import com.flyfishxu.kadb.mdns.MdnsServiceType

internal fun parseMdnsServiceType(serviceType: String?): MdnsServiceType? {
    val normalized = serviceType.normalizeMdnsServiceType()
    return MdnsServiceType.entries.firstOrNull { it.dnsType == normalized }
}

internal fun String?.normalizeMdnsServiceType(): String =
    this.orEmpty()
        .trim()
        .removeSuffix(".")
        .removeSuffix(".local")
        .lowercase()
