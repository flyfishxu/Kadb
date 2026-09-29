package com.flyfishxu.kadb.usb

import net.codecrete.usb.UsbAlternateInterface
import net.codecrete.usb.UsbDevice
import net.codecrete.usb.UsbDirection
import net.codecrete.usb.UsbEndpoint
import net.codecrete.usb.UsbException
import net.codecrete.usb.UsbInterface
import net.codecrete.usb.UsbTimeoutException
import net.codecrete.usb.UsbTransferType
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopUsbIoTest {
    @Test fun filtersInterfacesAndSelectsOnlyBulkAdbEndpoints() {
        val fake = FakeDevice()
        assertEquals(DesktopAdbEndpoints(3, 0, 1, 2, 512, 512), desktopAdbEndpoints(fake.device))
        fake.protocol = 2
        assertNull(desktopAdbEndpoints(fake.device))
        fake.protocol = 1
        fake.transferType = UsbTransferType.INTERRUPT
        assertNull(desktopAdbEndpoints(fake.device))
    }

    @Test fun failedClaimClosesOwnHandleButCannotCloseAnotherOwner() {
        val fake = FakeDevice().apply { failClaim = true }
        assertFailsWith<IOException> { DesktopUsbIo.open(fake.device) }
        assertEquals(1, fake.closes)
        fake.opened = true
        assertFailsWith<IOException> { DesktopUsbIo.open(fake.device) }
        assertEquals(1, fake.closes)
        assertTrue(fake.opened)
    }

    @Test fun closeAbortsPendingReadAndReleasesExactlyOnce() {
        val fake = FakeDevice()
        val started = CountDownLatch(1)
        fake.read = { started.countDown(); check(fake.aborted.await(2, TimeUnit.SECONDS)); throw UsbException("aborted") }
        val io = DesktopUsbIo.open(fake.device)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Boolean> {
                assertFailsWith<IOException> { io.receive(ByteArray(512), 0) }; true
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            io.close(); io.close()
            assertTrue(pending.get(1, TimeUnit.SECONDS))
            assertEquals(1, fake.releases)
            assertEquals(1, fake.closes)
            assertFalse(io.isOpen)
        } finally { io.close(); worker.shutdownNow() }
    }

    @Test fun mapsTimeoutsAndSendsExactPayloadIncludingZeroLengthPackets() {
        val fake = FakeDevice()
        val io = DesktopUsbIo.open(fake.device)
        try {
            io.send(byteArrayOf(9, 1, 2, 9), 1, 2, 100)
            io.send(byteArrayOf(), 0, 0, 100)
            assertContentEquals(byteArrayOf(1, 2), fake.sent[0])
            assertContentEquals(byteArrayOf(), fake.sent[1])
            fake.read = { throw UsbTimeoutException("timeout") }
            assertFailsWith<SocketTimeoutException> { io.receive(ByteArray(512), 100) }
        } finally { io.close() }
    }

    private class FakeDevice {
        var protocol = 1
        var transferType = UsbTransferType.BULK
        var opened = false
        var failClaim = false
        var closes = 0
        var releases = 0
        val aborted = CountDownLatch(1)
        val sent = mutableListOf<ByteArray>()
        var read: () -> ByteArray = { byteArrayOf(1) }
        private fun endpoint(direction: UsbDirection) = fake<UsbEndpoint> { name, _ -> when (name) {
            "getNumber" -> if (direction == UsbDirection.IN) 1 else 2
            "getDirection" -> direction
            "getTransferType" -> transferType
            "getPacketSize" -> 512
            else -> error(name)
        } }
        private val alternate = fake<UsbAlternateInterface> { name, _ -> when (name) {
            "getNumber" -> 0
            "getClassCode" -> 0xff
            "getSubclassCode" -> 0x42
            "getProtocolCode" -> protocol
            "getEndpoints" -> listOf(endpoint(UsbDirection.IN), endpoint(UsbDirection.OUT))
            else -> error(name)
        } }
        private val intf = fake<UsbInterface> { name, _ -> when (name) {
            "getNumber" -> 3
            "getAlternates" -> listOf(alternate)
            "getCurrentAlternate" -> alternate
            else -> error(name)
        } }
        val device = fake<UsbDevice> { name, args -> when (name) {
            "getInterfaces" -> listOf(intf)
            "getInterface" -> intf
            "isConnected" -> true
            "isOpened" -> opened
            "open" -> { opened = true; null }
            "claimInterface" -> { if (failClaim) throw UsbException("busy"); null }
            "releaseInterface" -> { releases++; null }
            "close" -> { closes++; opened = false; null }
            "abortTransfers" -> { aborted.countDown(); null }
            "transferIn" -> read()
            "transferOut" -> {
                val data = args[1] as ByteArray; val offset = args[2] as Int; val size = args[3] as Int
                sent.add(data.copyOfRange(offset, offset + size)); null
            }
            else -> error(name)
        } }
    }

    companion object {
        private inline fun <reified T> fake(crossinline action: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "USB fixture"
                    else -> action(method.name, args ?: emptyArray())
                }
            } as T
    }
}
