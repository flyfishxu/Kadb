package com.flyfishxu.kadb.mdns.internal

import com.flyfishxu.kadb.mdns.MdnsServiceType

internal data class MdnsServiceKey(
    val name: String,
    val serviceType: MdnsServiceType
)
