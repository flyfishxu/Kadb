/*
 * Copyright (c) MuntashirAkon
 * Copyright (c) IndiTheo
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.flyfishxu.kadb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ext.SdkExtensions
import java.io.IOException
import java.net.*
import java.util.*

/**
 * Automatic discovery of ADB daemons using mDNS.
 */
class KadbMdns(
    private val context: Context,
    private val serviceType: ServiceType = ServiceType.ADB,
    private val listener: OnServiceDiscoveredListener
) {
    enum class ServiceType(val type: String) {
        ADB("adb"),
        TLS_PAIRING("adb-tls-pairing"),
        TLS_CONNECT("adb-tls-connect")
    }

    interface OnServiceDiscoveredListener {
        /**
         * Called when a service is discovered or updated.
         * @param serviceName The name of the discovered service.
         * @param host The host address of the discovered service.
         * @param port The port of the discovered service, or -1 if the service is lost.
         */
        fun onServiceChanged(serviceName: String, host: InetAddress?, port: Int)
    }

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveryListener = DiscoveryListener()
    private val fullServiceType = "_${serviceType.type}._tcp"

    private var isRegistered = false
    private var isRunning = false
    private var lastServiceName: String? = null

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        if (!isRegistered) {
            nsdManager.discoverServices(fullServiceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        if (isRegistered) {
            nsdManager.stopServiceDiscovery(discoveryListener)
        }
    }

    fun isDiscoveryRunning(): Boolean = isRunning

    private inner class DiscoveryListener : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            isRegistered = true
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            isRunning = false
        }

        override fun onDiscoveryStopped(serviceType: String) {
            isRegistered = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (isRegisterServiceInfoCallbackSupported()) {
                nsdManager.registerServiceInfoCallback(serviceInfo, context.mainExecutor, object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                    override fun onServiceUpdated(resolvedServiceInfo: NsdServiceInfo) {
                        handleResolvedService(resolvedServiceInfo)
                    }
                    override fun onServiceLost() {
                        handleServiceLost(serviceInfo)
                    }
                    override fun onServiceInfoCallbackUnregistered() {}
                })
            } else {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                        handleResolvedService(resolvedServiceInfo)
                    }
                })
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (!isRegisterServiceInfoCallbackSupported()) {
                handleServiceLost(serviceInfo)
            }
        }
    }

    private fun handleResolvedService(resolvedServiceInfo: NsdServiceInfo) {
        if (!isRunning) return

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            for (networkInterface in Collections.list(interfaces)) {
                for (inetAddress in Collections.list(networkInterface.inetAddresses)) {
                    val resolvedHost = if (isRegisterServiceInfoCallbackSupported()) {
                        resolvedServiceInfo.hostAddresses.firstOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        resolvedServiceInfo.host
                    }

                    if (inetAddress.hostAddress == resolvedHost?.hostAddress &&
                        isPortOccupied(resolvedServiceInfo.port)
                    ) {
                        lastServiceName = resolvedServiceInfo.serviceName
                        listener.onServiceChanged(resolvedServiceInfo.serviceName, resolvedHost, resolvedServiceInfo.port)
                        return
                    }
                }
            }
        } catch (_: SocketException) {
            // Ignore
        }
    }

    private fun handleServiceLost(serviceInfo: NsdServiceInfo) {
        if (lastServiceName != null && lastServiceName == serviceInfo.serviceName) {
            val host = if (isRegisterServiceInfoCallbackSupported()) {
                serviceInfo.hostAddresses.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                serviceInfo.host
            }
            listener.onServiceChanged(serviceInfo.serviceName, host, -1)
        }
    }

    private fun isPortOccupied(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(getHostIpAddress(), port), 1)
                false
            }
        } catch (_: IOException) {
            true
        }
    }

    private fun getHostIpAddress(): String {
        if (isEmulator()) {
            return "10.0.2.2"
        }
        val ipAddress = InetAddress.getLoopbackAddress().hostAddress
        return if (ipAddress == null || ipAddress == "::1") "127.0.0.1" else ipAddress
    }

    private fun isRegisterServiceInfoCallbackSupported(): Boolean {
        return Build.VERSION.SDK_INT >= 37 || (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
                )
    }

    private fun isEmulator(): Boolean {
        return (Build.MODEL.contains("Emulator")
            || Build.PRODUCT.contains("emulator")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("cuttlefish")
            || Build.HARDWARE.contains("ranchu")
            || Build.MODEL.contains("Android SDK")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.PRODUCT.contains("sdk_google")
            || Build.MODEL.contains("google_sdk")
            || Build.PRODUCT.contains("vbox86p")
        )
    }
}
