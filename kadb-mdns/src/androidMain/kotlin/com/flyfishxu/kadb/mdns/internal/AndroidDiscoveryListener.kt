package com.flyfishxu.kadb.mdns.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.mdns.MdnsStatus

internal class AndroidDiscoveryListener(
    private val serviceType: MdnsServiceType,
    private val onStatusChanged: (MdnsStatus) -> Unit,
    private val onServiceFound: (NsdServiceInfo, MdnsServiceType) -> Unit,
    private val onServiceLost: (NsdServiceInfo, MdnsServiceType) -> Unit
) : NsdManager.DiscoveryListener {
    override fun onDiscoveryStarted(serviceType: String) {
        onStatusChanged(MdnsStatus.STARTED)
    }

    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        onStatusChanged(MdnsStatus.FAILED)
    }

    override fun onDiscoveryStopped(serviceType: String) {
        onStatusChanged(MdnsStatus.STOPPED)
    }

    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        onStatusChanged(MdnsStatus.FAILED)
    }

    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        val foundType = parseMdnsServiceType(serviceInfo.serviceType)
        if (foundType != serviceType) return
        onServiceFound(serviceInfo, serviceType)
    }

    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        val lostType = parseMdnsServiceType(serviceInfo.serviceType)
        if (lostType != null && lostType != serviceType) return
        onServiceLost(serviceInfo, serviceType)
    }
}
