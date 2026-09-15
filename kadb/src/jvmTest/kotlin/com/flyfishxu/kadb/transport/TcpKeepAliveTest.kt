package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.TcpKeepAlive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.TimeUnit.SECONDS
import java.net.ServerSocket
import java.net.SocketOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TcpKeepAliveTest {
    @Test fun configuresSupportedKernelOptionsWithoutClosingChannel() {
        val policy = TcpKeepAlive(5, 3, 4)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            AsynchronousSocketChannel.open().use { channel ->
                channel.connect(InetSocketAddress("127.0.0.1", server.localPort)).get(2, SECONDS)
                configureTcpKeepAlive(channel, policy)
                server.accept().use { peer ->
                    assertTrue(channel.isOpen)
                    val expected = mapOf("TCP_KEEPIDLE" to 5, "TCP_KEEPINTERVAL" to 3, "TCP_KEEPCOUNT" to 4)
                    for ((name, value) in expected) {
                        @Suppress("UNCHECKED_CAST")
                        val option = channel.supportedOptions().firstOrNull { it.name() == name } as? SocketOption<Int> ?: continue
                        assertEquals(value, channel.getOption(option), name)
                    }
                    channel.write(ByteBuffer.wrap(byteArrayOf(42))).get(2, SECONDS)
                    peer.soTimeout = 2000
                    assertEquals(42, peer.getInputStream().read())
                }
            }
        }
    }
}
