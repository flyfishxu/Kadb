package com.flyfishxu.kadb.mdns.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

internal class AndroidResolveListener(
    private val onServiceResolved: (NsdServiceInfo) -> Unit,
    private val onResolveFailed: (Int) -> Unit
) : NsdManager.ResolveListener {
    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        onResolveFailed(errorCode)
    }

    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
        onServiceResolved(serviceInfo)
    }
}
