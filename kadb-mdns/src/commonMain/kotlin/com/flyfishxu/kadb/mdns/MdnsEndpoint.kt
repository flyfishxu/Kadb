package com.flyfishxu.kadb.mdns

data class MdnsEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val serviceType: MdnsServiceType
)
