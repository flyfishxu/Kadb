package com.flyfishxu.kadb.transport

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

class PlainBlockingChannelTest {
    @Test fun slicedAndDirectBuffersPreservePositionsAndBytes(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val payload = ByteArray(130_000) { (it % 251).toByte() }
            val peer = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val received = socket.getInputStream().readNBytes(payload.size * 2)
                    assertContentEquals(payload + payload, received)
                    socket.getOutputStream().write(received)
                }
            }
            val channel = PlainBlockingChannel.connect("127.0.0.1", server.localPort, 3000)
            try {
                val sliced = ByteBuffer.wrap(ByteArray(payload.size + 17)).apply { position(17) }.slice()
                sliced.put(payload).flip()
                channel.writeExactly(sliced, 3, SECONDS)
                assertEquals(payload.size, sliced.position())
                val direct = ByteBuffer.allocateDirect(payload.size).apply { put(payload); flip() }
                channel.writeExactly(direct, 3, SECONDS)
                assertEquals(payload.size, direct.position())
                sliced.clear()
                channel.readExactly(sliced, 3, SECONDS)
                direct.clear()
                channel.readExactly(direct, 3, SECONDS)
                sliced.flip()
                direct.flip()
                assertContentEquals(payload, ByteArray(payload.size).also { sliced.get(it) })
                assertContentEquals(payload, ByteArray(payload.size).also { direct.get(it) })
                peer.get(3, SECONDS)
            } finally {
                channel.close()
                executor.shutdownNow()
            }
        }
    }
}
