@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.flyfishxu.kadb.mdns

import com.flyfishxu.kadb.mdns.internal.MdnsRegistry
import com.flyfishxu.kadb.mdns.internal.parseMdnsServiceType
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.atomics.AtomicLong
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSThread
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.getnameinfo
import platform.posix.sockaddr
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.pointed

/**
 * Bonjour discovery on iOS. Add NSLocalNetworkUsageDescription and the requested
 * NSBonjourServices entries to the host application's Info.plist.
 *
 * Foundation requires a running run loop, so browsing and its registry updates
 * are serialized on the main thread even when start/stop are called elsewhere.
 */
class KadbMdnsIos(
    config: MdnsConfig = MdnsConfig(),
) : KadbMdns {
    private val discovery = BonjourDiscovery(config)

    override val state: StateFlow<MdnsDiscoveryState> = discovery.state
    override fun start() = discovery.start()
    override fun stop() = discovery.stop()
}

private class BonjourDiscovery(
    private val config: MdnsConfig,
) : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {
    private val registry = MdnsRegistry(config)
    private val browsers = mutableMapOf<NSNetServiceBrowser, MdnsServiceType>()
    private val services = mutableMapOf<ServiceKey, NSNetService>()
    private val lifecycleGeneration = AtomicLong(0L)
    private var started = false

    val state: StateFlow<MdnsDiscoveryState> = registry.state

    fun start() {
        val generation = lifecycleGeneration.fetchAndAdd(1L) + 1L
        onMainThread {
            if (lifecycleGeneration.load() != generation || started) return@onMainThread
            started = true
            registry.starting()
            config.serviceTypes.forEach { type ->
                NSNetServiceBrowser().also { browser ->
                    browsers[browser] = type
                    browser.delegate = this
                    browser.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
                    browser.searchForServicesOfType(type.dnsType, "local.")
                }
            }
            if (browsers.isEmpty()) registry.started()
        }
    }

    fun stop() {
        val generation = lifecycleGeneration.fetchAndAdd(1L) + 1L
        onMainThread {
            // A later main-thread stop must also cancel an earlier queued background start.
            if (lifecycleGeneration.load() != generation) return@onMainThread
            stopBrowsing()
            registry.stopped()
        }
    }

    override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
        if (started && browser in browsers) registry.started()
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean,
    ) {
        val type = browsers[browser] ?: return
        if (!started) return
        val key = ServiceKey(didFindService.name, type)
        services.put(key, didFindService)?.let(::stopResolving)
        didFindService.delegate = this
        didFindService.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        didFindService.resolveWithTimeout(10.0)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didRemoveService: NSNetService,
        moreComing: Boolean,
    ) {
        val type = browsers[browser] ?: return
        if (!started) return
        services.remove(ServiceKey(didRemoveService.name, type))?.let(::stopResolving)
        registry.remove(didRemoveService.name, type)
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
        if (!started || browser !in browsers) return
        stopBrowsing()
        registry.failed()
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        val type = parseMdnsServiceType(sender.type) ?: return
        if (!started || services[ServiceKey(sender.name, type)] !== sender) return
        val addresses = sender.addresses.orEmpty().mapNotNull { (it as? NSData)?.numericAddress() }
        val host = if (config.preferIpv4) {
            addresses.firstOrNull { it.second == AF_INET }?.first ?: addresses.firstOrNull()?.first
        } else {
            addresses.firstOrNull()?.first
        } ?: return
        registry.upsert(MdnsEndpoint(sender.name, host, sender.port.toInt(), type))
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        val type = parseMdnsServiceType(sender.type) ?: return
        val key = ServiceKey(sender.name, type)
        if (!started || services[key] !== sender) return
        services.remove(key)
        stopResolving(sender)
        registry.remove(sender.name, type)
    }

    private fun stopBrowsing() {
        started = false
        val currentBrowsers = browsers.keys.toList()
        browsers.clear()
        currentBrowsers.forEach {
            it.delegate = null
            it.stop()
            it.removeFromRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        }
        val currentServices = services.values.toList()
        services.clear()
        currentServices.forEach(::stopResolving)
    }

    private fun stopResolving(service: NSNetService) {
        service.delegate = null
        service.stop()
        service.removeFromRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
    }

    private data class ServiceKey(val name: String, val type: MdnsServiceType)
}

private fun onMainThread(block: () -> Unit) {
    if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
}

private fun NSData.numericAddress(): Pair<String, Int>? = memScoped {
    if (length < 2u) return@memScoped null
    val address = bytes?.reinterpret<sockaddr>() ?: return@memScoped null
    val family = address.pointed.sa_family.toInt()
    if (family != AF_INET && family != AF_INET6) return@memScoped null
    val addressLength = address.pointed.sa_len.toUInt()
    if (addressLength < 2u || addressLength.toULong() > length) return@memScoped null
    val buffer = allocArray<ByteVar>(NI_MAXHOST)
    if (getnameinfo(address, addressLength, buffer, NI_MAXHOST.toUInt(), null, 0u, NI_NUMERICHOST) != 0) {
        return@memScoped null
    }
    buffer.toKString() to family
}
