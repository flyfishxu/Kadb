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

package com.flyfishxu.kadb.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.flyfishxu.kadb.mdns.internal.AndroidDiscoveryListener
import com.flyfishxu.kadb.mdns.internal.AndroidResolveListener
import com.flyfishxu.kadb.mdns.internal.AndroidServiceInfoCallback
import com.flyfishxu.kadb.mdns.internal.MdnsRegistry
import com.flyfishxu.kadb.mdns.internal.MdnsServiceKey
import com.flyfishxu.kadb.mdns.internal.toMdnsEndpoint
import com.flyfishxu.kadb.mdns.internal.toMdnsServiceKey
import kotlinx.coroutines.flow.StateFlow

class KadbMdnsAndroid(
    context: Context,
    private val config: MdnsConfig = MdnsConfig()
) : KadbMdns {
    private val applicationContext = context.applicationContext
    private val nsdManager = applicationContext.getSystemService(NsdManager::class.java)
    private val registry = MdnsRegistry(config)
    private val callbacks = mutableMapOf<MdnsServiceKey, NsdManager.ServiceInfoCallback>()
    private val lock = Any()
    private var started = false
    private var closed = false

    private val discoveryListeners = config.serviceTypes.associateWith { serviceType ->
        AndroidDiscoveryListener(
            serviceType = serviceType,
            onStatusChangedAction = ::onStatusChanged,
            onServiceFoundAction = ::onServiceFound,
            onServiceLostAction = ::onServiceLost
        )
    }

    override val state: StateFlow<MdnsDiscoveryState> = registry.state

    override fun start() {
        val listeners = synchronized(lock) {
            if (closed || started) return
            started = true
            callbacks.clear()
            registry.starting()
            discoveryListeners.toList()
        }

        if (listeners.isEmpty()) {
            onStatusChanged(MdnsStatus.STARTED)
            return
        }

        listeners.forEach { (serviceType, listener) ->
            runCatching {
                nsdManager.discoverServices(
                    serviceType.dnsType,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            }.onFailure {
                onStatusChanged(MdnsStatus.FAILED)
            }
        }
    }

    override fun stop() {
        val listeners = synchronized(lock) {
            if (!started) return
            started = false
            discoveryListeners.values.toList()
        }

        listeners.forEach { listener ->
            runCatching {
                nsdManager.stopServiceDiscovery(listener)
            }
        }
        clearServiceInfoCallbacks()

        synchronized(lock) {
            callbacks.clear()
            registry.stopped()
        }
    }

    override fun close() {
        stop()
        synchronized(lock) {
            closed = true
        }
    }

    private fun onStatusChanged(status: MdnsStatus) {
        synchronized(lock) {
            when (status) {
                MdnsStatus.STARTING -> if (started) registry.starting()
                MdnsStatus.STARTED -> if (started) registry.started()
                MdnsStatus.STOPPED -> if (!started) registry.stopped()
                MdnsStatus.FAILED -> registry.failed()
            }
        }
    }

    private fun onServiceFound(serviceInfo: NsdServiceInfo, serviceType: MdnsServiceType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerServiceInfoCallback(serviceInfo, serviceType)
        } else {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(
                serviceInfo,
                AndroidResolveListener(
                    onServiceResolvedAction = { resolvedInfo -> upsertService(resolvedInfo, serviceType) },
                    onResolveFailedAction = { onStatusChanged(MdnsStatus.FAILED) }
                )
            )
        }
    }

    private fun onServiceLost(serviceInfo: NsdServiceInfo, serviceType: MdnsServiceType) {
        val key = serviceInfo.toMdnsServiceKey(fallbackServiceType = serviceType) ?: return
        unregisterServiceInfoCallback(key)
        synchronized(lock) {
            if (started) {
                registry.remove(name = key.name, serviceType = key.serviceType)
            }
        }
    }

    private fun upsertService(serviceInfo: NsdServiceInfo, fallbackServiceType: MdnsServiceType) {
        val endpoint = serviceInfo.toMdnsEndpoint(
            config = config,
            fallbackServiceType = fallbackServiceType
        ) ?: return
        synchronized(lock) {
            if (started) {
                registry.upsert(endpoint)
            }
        }
    }

    private fun registerServiceInfoCallback(
        serviceInfo: NsdServiceInfo,
        serviceType: MdnsServiceType
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val key = serviceInfo.toMdnsServiceKey(fallbackServiceType = serviceType) ?: return
        val callback = AndroidServiceInfoCallback(
            onRegistrationFailedAction = { onStatusChanged(MdnsStatus.FAILED) },
            onServiceUpdatedAction = { updatedInfo -> upsertService(updatedInfo, serviceType) },
            onServiceLostAction = { removeServiceByKey(key) },
            onUnregisteredAction = {
                synchronized(lock) {
                    callbacks.remove(key)
                }
            }
        )

        val shouldRegister = synchronized(lock) {
            if (!started || closed || callbacks.containsKey(key)) {
                false
            } else {
                callbacks[key] = callback
                true
            }
        }
        if (!shouldRegister) return

        runCatching {
            nsdManager.registerServiceInfoCallback(serviceInfo, applicationContext.mainExecutor, callback)
        }.onFailure {
            synchronized(lock) {
                callbacks.remove(key, callback)
            }
            onStatusChanged(MdnsStatus.FAILED)
        }
    }

    private fun removeServiceByKey(key: MdnsServiceKey) {
        synchronized(lock) {
            if (started) {
                registry.remove(name = key.name, serviceType = key.serviceType)
            }
        }
        unregisterServiceInfoCallback(key)
    }

    private fun unregisterServiceInfoCallback(key: MdnsServiceKey) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = synchronized(lock) { callbacks.remove(key) } ?: return
        runCatching {
            nsdManager.unregisterServiceInfoCallback(callback)
        }
    }

    private fun clearServiceInfoCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val currentCallbacks = synchronized(lock) {
            callbacks.values.toList().also { callbacks.clear() }
        }
        currentCallbacks.forEach { callback ->
            runCatching {
                nsdManager.unregisterServiceInfoCallback(callback)
            }
        }
    }
}
