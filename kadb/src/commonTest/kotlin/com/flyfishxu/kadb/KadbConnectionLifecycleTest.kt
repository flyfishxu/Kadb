package com.flyfishxu.kadb

import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toOkioPath
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KadbConnectionLifecycleTest {
    @Test fun concurrentFirstCommandsShareOneTransport() = withPeer { server, client ->
        val workers = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val calls = (1..8).map {
                workers.submit<Boolean> {
                    ready.countDown()
                    assertTrue(start.await(3, TimeUnit.SECONDS))
                    client.supportsFeature("shell_v2")
                }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
            server.accept().use { socket ->
                assertEquals("CNXN", readCommand(socket))
                connectReply(socket)
                calls.forEach { assertTrue(it.get(3, TimeUnit.SECONDS)) }
                assertNoExtraConnection(server)
            }
        } finally { start.countDown(); workers.shutdownNow() }
    }

    @Test fun closeDuringHandshakeCannotRestoreTheConnection() = withPeer { server, client ->
        val worker = Executors.newSingleThreadExecutor()
        try {
            val call = worker.submit<Boolean> {
                assertFailsWith<IOException> { client.supportsFeature("shell_v2") }
                true
            }
            server.accept().use { socket ->
                assertEquals("CNXN", readCommand(socket))
                client.close()
                assertTrue(call.get(1, TimeUnit.SECONDS))
                assertEquals(-1, socket.getInputStream().read())
                assertFalse(client.connectionCheck())
                assertFailsWith<IOException> { client.open("shell:echo closed") }
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun lostOpenReplyDoesNotReplayTheCommand() = withPeer { server, client ->
        val worker = Executors.newSingleThreadExecutor()
        try {
            val call = worker.submit<Boolean> {
                assertFailsWith<IOException> { client.open("shell:echo side-effect") }
                true
            }
            server.accept().use { socket ->
                assertEquals("CNXN", readCommand(socket))
                connectReply(socket)
                assertEquals("OPEN", readCommand(socket))
                // The service could already be running; lose its OKAY reply.
            }
            assertTrue(call.get(3, TimeUnit.SECONDS))
            assertNoExtraConnection(server)
            assertFalse(client.connectionCheck())
        } finally { worker.shutdownNow() }
    }

    @Test fun resetClosesOldTransportAndKeepsClientReusable() = withPeer { server, client ->
        val worker = Executors.newSingleThreadExecutor()
        try {
            val call = worker.submit<Boolean> {
                assertTrue(client.supportsFeature("shell_v2"))
                client.resetConnection()
                assertFalse(client.connectionCheck())
                client.supportsFeature("shell_v2")
            }
            repeat(2) { attempt ->
                server.accept().use { socket ->
                    assertEquals("CNXN", readCommand(socket))
                    connectReply(socket)
                    if (attempt == 0) assertEquals(-1, socket.getInputStream().read())
                    else assertTrue(call.get(3, TimeUnit.SECONDS))
                }
            }
            assertNoExtraConnection(server)
        } finally { worker.shutdownNow() }
    }

    private fun withPeer(block: (ServerSocket, Kadb) -> Unit) {
        val directory = Files.createTempDirectory("kadb-lifecycle-").toFile()
        try {
            KadbCert.configure(OkioFilePrivateKeyStore(directory.resolve("adbkey").toOkioPath()))
            KadbCert.ensureReady()
            ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 3000
                Kadb.create("127.0.0.1", server.localPort, connectTimeout = 1000,
                    socketTimeout = 2000).use { block(server, it) }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun assertNoExtraConnection(server: ServerSocket) {
        server.soTimeout = 200
        assertFailsWith<SocketTimeoutException> {
            server.accept().use { error("Unexpected extra ADB connection") }
        }
    }

    private fun readCommand(socket: Socket): String {
        socket.soTimeout = 3000
        val input = DataInputStream(socket.getInputStream())
        val header = ByteArray(24).also(input::readFully)
        val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        input.readFully(ByteArray(size))
        return String(header, 0, 4, Charsets.US_ASCII)
    }

    private fun connectReply(socket: Socket) {
        val payload = "device::features=shell_v2;\u0000".toByteArray()
        val command = ByteBuffer.wrap("CNXN".toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(0x01000001).putInt(4096).putInt(payload.size)
            .putInt(0).putInt(command.inv()).array()
        socket.getOutputStream().apply { write(header); write(payload); flush() }
    }
}
