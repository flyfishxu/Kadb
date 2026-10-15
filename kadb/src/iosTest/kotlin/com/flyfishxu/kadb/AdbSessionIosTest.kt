@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flyfishxu.kadb

import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader
import com.flyfishxu.kadb.core.AdbWriter
import com.flyfishxu.kadb.transport.PosixTransportChannel
import com.flyfishxu.kadb.transport.asOkioSink
import com.flyfishxu.kadb.transport.asOkioSource
import kotlinx.cinterop.*
import okio.use
import platform.CoreFoundation.CFSwapInt16BigToHost
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.posix.*
import kotlin.test.*

class AdbSessionIosTest {
    @Test fun commonShellRunsThroughIosSocketAndClosesCleanly(): Unit = memScoped {
        val listener = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(listener >= 0)
        val address = alloc<sockaddr_in>().apply {
            sin_len = sizeOf<sockaddr_in>().convert()
            sin_family = AF_INET.convert()
            sin_port = 0u
            sin_addr.s_addr = 0x0100007fu // 127.0.0.1 in network byte order on arm64
        }
        val completion = NSCondition()
        var completed = false
        var failure: Throwable? = null
        try {
            assertEquals(0, bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()))
            assertEquals(0, listen(listener, 1))
            val length = alloc<socklen_tVar> { value = sizeOf<sockaddr_in>().convert() }
            assertEquals(0, getsockname(listener, address.ptr.reinterpret(), length.ptr))
            val port = CFSwapInt16BigToHost(address.sin_port).toInt()
            val output = "你好 from iOS\n".encodeToByteArray()
            NSThread {
                try {
                    val fd = accept(listener, null, null)
                    check(fd >= 0)
                    PosixTransportChannel(fd).use { channel ->
                        val reader = AdbReader(channel.asOkioSource(3000))
                        val writer = AdbWriter(channel.asOkioSink(3000))
                        assertEquals(AdbProtocol.CMD_CNXN, reader.readMessage().command)
                        val banner = "device::features=\u0000".encodeToByteArray()
                        writer.write(AdbProtocol.CMD_CNXN, AdbProtocol.A_VERSION, 4096, banner, 0, banner.size)
                        val open = reader.readMessage()
                        assertEquals(AdbProtocol.CMD_OPEN, open.command)
                        assertEquals("shell:echo test\u0000", open.payload.decodeToString())
                        writer.writeOkay(7, open.arg0)
                        writer.writeWrite(7, open.arg0, output, 0, output.size)
                        assertEquals(AdbProtocol.CMD_OKAY, reader.readMessage().command)
                        writer.writeClose(7, open.arg0)
                        // Keep the connection open until the caller closes it; idle EOF is separate from stream EOF.
                        runCatching { reader.readMessage() }
                    }
                } catch (error: Throwable) {
                    completion.lock()
                    failure = error
                    completion.unlock()
                } finally {
                    completion.lock()
                    completed = true
                    completion.signal()
                    completion.unlock()
                }
            }.start()
            Kadb.create("127.0.0.1", port, connectTimeout = 3000, socketTimeout = 3000).use { client ->
                assertEquals(output.decodeToString(), client.shell("echo test").output)
                assertTrue(client.connectionCheck())
            }
            completion.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
                while (!completed) assertTrue(completion.waitUntilDate(deadline), "Peer did not finish")
                failure?.let { throw it }
            } finally {
                completion.unlock()
            }
        } finally {
            close(listener)
        }
    }
}
