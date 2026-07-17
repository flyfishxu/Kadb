package com.flyfishxu.kadb

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KadbResourceTest {
    @Test
    fun emptyInstallSetIsRejectedWithoutOpeningAConnection() {
        Kadb.create("127.0.0.1", 1).use { kadb ->
            assertFailsWith<IllegalArgumentException> {
                kadb.installMultiple(emptyList())
            }
        }
    }

    @Test
    fun failedFileTransfersDoNotLeakLocalFileDescriptors() {
        val procFileDescriptors = File("/proc/self/fd")
        if (!procFileDescriptors.isDirectory) return

        val tempDir = createTempDirectory("kadb-resource-test").toFile()
        val source = File(tempDir, "source.bin").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        val destination = File(tempDir, "destination.bin")
        val attemptsPerDirection = 24
        val totalConnections = 1 + attemptsPerDirection * 2

        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val acceptThread = thread(name = "kadb-failing-peer") {
                repeat(totalConnections) {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(24))
                    }
                }
            }

            Kadb.create("127.0.0.1", server.localPort, connectTimeout = 2_000, socketTimeout = 2_000).use { kadb ->
                runCatching { kadb.push(source, "/data/local/tmp/warmup.bin") }
                val before = descriptorCount(procFileDescriptors)

                repeat(attemptsPerDirection) {
                    runCatching { kadb.push(source, "/data/local/tmp/source.bin") }
                }
                repeat(attemptsPerDirection) {
                    runCatching { kadb.pull(destination, "/data/local/tmp/source.bin") }
                }

                val leaked = descriptorCount(procFileDescriptors) - before
                assertTrue(leaked <= 4, "Failed transfers leaked $leaked file descriptors")
            }

            acceptThread.join(10_000)
            assertTrue(!acceptThread.isAlive, "Synthetic ADB peer did not finish")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun tcpForwardReportsBindFailureWithoutWaitingForTimeout() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { occupiedPort ->
            Kadb.create("127.0.0.1", 1).use { kadb ->
                val start = System.nanoTime()
                assertFailsWith<IOException> {
                    kadb.tcpForward(occupiedPort.localPort, targetPort = 12345)
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                assertTrue(elapsedMs < 2_000, "Bind failure took ${elapsedMs}ms to surface")
            }
        }
    }

    private fun descriptorCount(directory: File): Int = directory.list()?.size ?: 0
}
