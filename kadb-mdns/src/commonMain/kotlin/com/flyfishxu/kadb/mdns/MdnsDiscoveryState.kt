package com.flyfishxu.kadb.mdns

data class MdnsDiscoveryState(
    val status: MdnsStatus = MdnsStatus.STOPPED,
    val loading: Boolean = false,
    val connectDevices: List<MdnsEndpoint> = emptyList(),
    val pairDevices: List<MdnsEndpoint> = emptyList()
) {
    val allDevices: List<MdnsEndpoint>
        get() = connectDevices + pairDevices
}
