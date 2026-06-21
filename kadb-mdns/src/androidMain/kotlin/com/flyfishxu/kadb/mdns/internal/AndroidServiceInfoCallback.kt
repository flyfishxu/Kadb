package com.flyfishxu.kadb.mdns.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

internal class AndroidServiceInfoCallback(
    private val onRegistrationFailedAction: (Int) -> Unit,
    private val onServiceUpdatedAction: (NsdServiceInfo) -> Unit,
    private val onServiceLostAction: () -> Unit,
    private val onUnregisteredAction: () -> Unit
) : NsdManager.ServiceInfoCallback {
    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
        onRegistrationFailedAction(errorCode)
    }

    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
        onServiceUpdatedAction(serviceInfo)
    }

    override fun onServiceLost() {
        onServiceLostAction()
    }

    override fun onServiceInfoCallbackUnregistered() {
        onUnregisteredAction()
    }
}
