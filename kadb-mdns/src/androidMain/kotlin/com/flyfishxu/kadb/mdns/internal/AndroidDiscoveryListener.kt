/*
 * Copyright (c) 2024 Flyfish-Xu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by IndiTheo and IndiDevs (2026)
 */

package com.flyfishxu.kadb.mdns.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.mdns.MdnsStatus

internal class AndroidDiscoveryListener(
    private val serviceType: MdnsServiceType,
    private val onStatusChangedAction: (MdnsStatus) -> Unit,
    private val onServiceFoundAction: (NsdServiceInfo, MdnsServiceType) -> Unit,
    private val onServiceLostAction: (NsdServiceInfo, MdnsServiceType) -> Unit
) : NsdManager.DiscoveryListener {
    override fun onDiscoveryStarted(serviceType: String) {
        onStatusChangedAction(MdnsStatus.STARTED)
    }

    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        onStatusChangedAction(MdnsStatus.FAILED)
    }

    override fun onDiscoveryStopped(serviceType: String) {
        onStatusChangedAction(MdnsStatus.STOPPED)
    }

    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        onStatusChangedAction(MdnsStatus.FAILED)
    }

    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        val foundType = parseMdnsServiceType(serviceInfo.serviceType)
        if (foundType != serviceType) return
        onServiceFoundAction(serviceInfo, serviceType)
    }

    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        val lostType = parseMdnsServiceType(serviceInfo.serviceType)
        if (lostType != null && lostType != serviceType) return
        onServiceLostAction(serviceInfo, serviceType)
    }
}
