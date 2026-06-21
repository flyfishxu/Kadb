package com.flyfishxu.kadb.mdns.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

internal class AndroidResolveListener(
    private val onServiceResolvedAction: (NsdServiceInfo) -> Unit,
    private val onResolveFailedAction: (Int) -> Unit
) : NsdManager.ResolveListener {
    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        onResolveFailedAction(errorCode)
    }

    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
        onServiceResolvedAction(serviceInfo)
    }
}
