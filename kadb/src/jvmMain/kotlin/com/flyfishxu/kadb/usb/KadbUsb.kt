package com.flyfishxu.kadb.usb

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.KadbOptions
import com.flyfishxu.kadb.transport.UsbBulkIo
import com.flyfishxu.kadb.transport.UsbPacketChannel
import net.codecrete.usb.Usb
import net.codecrete.usb.UsbDevice
import net.codecrete.usb.UsbDirection
import net.codecrete.usb.UsbException
import net.codecrete.usb.UsbTimeoutException
import net.codecrete.usb.UsbTransferType
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** An attached desktop USB device. Re-enumerate after unplug/replug; do not persist this handle. */
class KadbUsbDevice internal constructor(internal val nativeDevice: UsbDevice) {
    val vendorId: Int get() = nativeDevice.vendorId
    val productId: Int get() = nativeDevice.productId
    val manufacturer: String? get() = nativeDevice.manufacturer
    val productName: String? get() = nativeDevice.product
    val serialNumber: String? get() = nativeDevice.serialNumber
    val isConnected: Boolean get() = nativeDevice.isConnected

    override fun toString(): String =
        "${productName ?: "ADB device"} (${vendorId.toString(16)}:${productId.toString(16)}, serial=$serialNumber)"
}

/** Direct USB ADB for desktop JVM (Java 25+, macOS/Linux/Windows). Does not use an adb server. */
object KadbUsb {
    /** Enumerates connected devices exposing an ADB bulk interface. Does not open or claim them. */
    fun devices(): List<KadbUsbDevice> = usbCall("enumerate devices") {
        Usb.getDevices().filter { it.isConnected && desktopAdbEndpoints(it) != null }
            .map(::KadbUsbDevice)
            .sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.serialNumber.orEmpty() }))
    }

    /**
     * Creates a lazy client. Close other owners (including adb) before the first command.
     * All operations on one physical device must share this instance's independent ADB streams.
     * Uses the configured KadbCert identity; the target may ask to allow USB debugging.
     */
    fun create(device: KadbUsbDevice, options: KadbOptions = KadbOptions()): Kadb {
        requireNotNull(desktopAdbEndpoints(device.nativeDevice)) { "Device has no USB ADB interface" }
        return Kadb.createWithTransport(options) { UsbPacketChannel(DesktopUsbIo.open(device.nativeDevice)) }
    }
}

internal data class DesktopAdbEndpoints(
    val interfaceNumber: Int,
    val alternateNumber: Int,
    val inputNumber: Int,
    val outputNumber: Int,
    val inputPacketSize: Int,
    val outputPacketSize: Int
)

internal fun desktopAdbEndpoints(device: UsbDevice): DesktopAdbEndpoints? {
    for (intf in device.interfaces) {
        for (alternate in intf.alternates) {
            if (alternate.classCode != 0xff || alternate.subclassCode != 0x42 || alternate.protocolCode != 1) continue
            val bulk = alternate.endpoints.filter { it.transferType == UsbTransferType.BULK }
            val input = bulk.firstOrNull { it.direction == UsbDirection.IN } ?: continue
            val output = bulk.firstOrNull { it.direction == UsbDirection.OUT } ?: continue
            if (input.packetSize <= 0 || output.packetSize <= 0) continue
            return DesktopAdbEndpoints(intf.number, alternate.number, input.number, output.number,
                input.packetSize, output.packetSize)
        }
    }
    return null
}

internal class DesktopUsbIo private constructor(
    private val device: UsbDevice,
    private val endpoints: DesktopAdbEndpoints
) : UsbBulkIo {
    private val closed = AtomicBoolean(false)
    override val isOpen: Boolean get() = !closed.get() && device.isConnected && device.isOpened
    override val inputPacketSize: Int get() = endpoints.inputPacketSize
    override val outputPacketSize: Int get() = endpoints.outputPacketSize

    override fun receive(buffer: ByteArray, timeoutMs: Int): Int = usbCall("read") {
        if (!isOpen) throw IOException("USB transport closed or disconnected")
        // transferIn receives at most one endpoint packet. UsbPacketChannel assembles
        // multiple packets into a frame without over-reading an aligned payload.
        val packet = device.transferIn(endpoints.inputNumber, timeoutMs)
        if (packet.size > buffer.size) throw IOException("USB packet exceeds receive buffer")
        packet.copyInto(buffer)
        packet.size
    }

    override fun send(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int = usbCall("write") {
        if (!isOpen) throw IOException("USB transport closed or disconnected")
        // The backend does not add ZLPs; UsbPacketChannel emits them at frame boundaries.
        device.transferOut(endpoints.outputNumber, buffer, offset, length, timeoutMs)
        length
    }

    override fun close() {
        synchronized(device) {
            // A second close must also wait for interface release to finish before
            // its caller is allowed to reclaim this device.
            if (!closed.compareAndSet(false, true)) return
            // Abort first so a blocked transfer cannot hold up release/close.
            runCatching { device.abortTransfers(UsbDirection.IN, endpoints.inputNumber) }
            runCatching { device.abortTransfers(UsbDirection.OUT, endpoints.outputNumber) }
            try { device.releaseInterface(endpoints.interfaceNumber) }
            finally { device.close() }
        }
    }

    companion object {
        fun open(device: UsbDevice): DesktopUsbIo = usbCall("open device (check USB permissions and close other USB owners such as adb)") {
            synchronized(device) {
                if (!device.isConnected) throw IOException("USB device disconnected; enumerate again")
                if (device.isOpened) throw IOException("USB device already opened; share the existing Kadb client")
                val endpoints = desktopAdbEndpoints(device) ?: throw IOException("USB ADB interface unavailable")
                device.open()
                try {
                    device.claimInterface(endpoints.interfaceNumber)
                    if (device.getInterface(endpoints.interfaceNumber).currentAlternate.number != endpoints.alternateNumber) {
                        device.selectAlternateSetting(endpoints.interfaceNumber, endpoints.alternateNumber)
                    }
                    DesktopUsbIo(device, endpoints)
                } catch (error: Throwable) {
                    runCatching { device.close() }
                    throw error
                }
            }
        }
    }
}

private inline fun <T> usbCall(operation: String, action: () -> T): T = try {
    action()
} catch (error: UsbTimeoutException) {
    throw SocketTimeoutException("USB $operation timed out").apply { initCause(error) }
} catch (error: UsbException) {
    throw IOException("USB $operation failed: ${error.message}", error)
}
