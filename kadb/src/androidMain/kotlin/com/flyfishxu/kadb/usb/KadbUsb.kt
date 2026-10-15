package com.flyfishxu.kadb.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.KadbOptions
import com.flyfishxu.kadb.transport.UsbBulkIo
import com.flyfishxu.kadb.transport.UsbPacketChannel
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android USB Host entry points. Permission prompts remain the calling app's responsibility. */
object KadbUsb {
    fun devices(manager: UsbManager): List<UsbDevice> =
        manager.deviceList.values.filter { endpoints(it) != null }.sortedBy { it.deviceName }

    fun isAdbDevice(device: UsbDevice): Boolean = endpoints(device) != null

    /**
     * Creates a lazy USB client using KadbCert's existing host identity. Call only after
     * UsbManager.hasPermission(device). The target must have USB debugging enabled.
     * Own one Kadb per device; concurrent operations use that client's independent ADB streams.
     * Close the client before opening another. After unplugging, enumerate and select again.
     */
    fun create(context: Context, device: UsbDevice, options: KadbOptions = KadbOptions()): Kadb {
        val app = context.applicationContext
        val manager = app.getSystemService(UsbManager::class.java)
        requireNotNull(endpoints(device)) { "Device has no USB ADB interface" }
        if (!manager.hasPermission(device)) throw SecurityException("USB device permission required")
        return Kadb.createWithTransport(options) { UsbPacketChannel(AndroidUsbIo.open(app, manager, device)) }
    }
}

private data class Endpoints(val intf: UsbInterface, val input: UsbEndpoint, val output: UsbEndpoint)

private fun endpoints(device: UsbDevice): Endpoints? {
    for (index in 0 until device.interfaceCount) {
        val intf = device.getInterface(index)
        if (intf.interfaceClass != 0xff || intf.interfaceSubclass != 0x42 || intf.interfaceProtocol != 1) continue
        val bulk = (0 until intf.endpointCount).map(intf::getEndpoint)
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: continue
        val output = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: continue
        if (input.maxPacketSize <= 0 || output.maxPacketSize <= 0) continue
        return Endpoints(intf, input, output)
    }
    return null
}

private class AndroidUsbIo(
    private val context: Context,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val endpoints: Endpoints
) : UsbBulkIo {
    private val lock = Any()
    private var activeRead: UsbRequest? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kadb-usb-timeout").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var registered = false
    override val isOpen get() = !closed
    override val inputPacketSize get() = endpoints.input.maxPacketSize
    override val outputPacketSize get() = endpoints.output.maxPacketSize
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val detached = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && detached?.deviceName == device.deviceName) close()
        }
    }

    override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        val bytes = ByteBuffer.allocateDirect(buffer.size)
        val request = UsbRequest()
        var expired = false
        val timeout = synchronized(lock) {
            if (closed) throw IOException("USB transport closed")
            if (!request.initialize(connection, endpoints.input)) throw IOException("USB read initialization failed")
            activeRead = request
            @Suppress("DEPRECATION")
            if (!request.queue(bytes, bytes.capacity())) {
                activeRead = null
                request.close()
                throw IOException("USB read queue failed")
            }
            if (timeoutMs <= 0) null else timer.schedule({
                synchronized(lock) {
                    if (activeRead === request) {
                        expired = true
                        request.cancel()
                    }
                }
            }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }
        try {
            // A queued UsbRequest can be cancelled even on API 23, unlike a blocking
            // infinite bulkTransfer. There is only one queued request on this connection.
            val completed = connection.requestWait()
            synchronized(lock) {
                if (expired) throw SocketTimeoutException("USB read timed out")
                if (closed || completed !== request) throw IOException("USB disconnected or read failed")
                val length = bytes.position()
                bytes.flip()
                bytes.get(buffer, 0, length)
                return length
            }
        } finally {
            synchronized(lock) {
                timeout?.cancel(false)
                if (activeRead === request) activeRead = null
                request.close()
            }
        }
    }

    override fun send(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (closed) throw IOException("USB transport closed")
        return connection.bulkTransfer(endpoints.output, buffer, offset, length, timeoutMs)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeRead?.cancel()
            timer.shutdownNow()
        }
        if (registered) runCatching { context.unregisterReceiver(detachReceiver) }
        try { connection.releaseInterface(endpoints.intf) } finally { connection.close() }
    }

    companion object {
        fun open(context: Context, manager: UsbManager, device: UsbDevice): AndroidUsbIo {
            if (!manager.hasPermission(device)) throw SecurityException("USB device permission required")
            val endpoints = endpoints(device) ?: throw IOException("USB ADB interface unavailable")
            val connection = manager.openDevice(device) ?: throw IOException("Cannot open USB device")
            val io = AndroidUsbIo(context, device, connection, endpoints)
            try {
                if (!connection.claimInterface(endpoints.intf, false)) throw IOException("USB ADB interface is busy")
                val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
                synchronized(io.lock) {
                    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(io.detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    else context.registerReceiver(io.detachReceiver, filter)
                    io.registered = true
                }
                if (manager.deviceList[device.deviceName] != device) throw IOException("USB disconnected")
                return io
            } catch (error: Throwable) {
                io.close()
                throw error
            }
        }
    }
}
