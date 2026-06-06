package com.flyfishxu.kadb.mdns.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

internal class AndroidServiceInfoCallback(
    private val onRegistrationFailed: (Int) -> Unit,
    private val onServiceUpdated: (NsdServiceInfo) -> Unit,
    private val onServiceLost: () -> Unit,
    private val onUnregistered: () -> Unit
) : NsdManager.ServiceInfoCallback {
    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
        onRegistrationFailed(errorCode)
    }

    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
        onServiceUpdated(serviceInfo)
    }

    override fun onServiceLost() {
        onServiceLost()
    }

    override fun onServiceInfoCallbackUnregistered() {
        onUnregistered()
    }
}
