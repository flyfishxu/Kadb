package com.flyfishxu.kadb.stream

import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader
import com.flyfishxu.kadb.core.AdbWriter
import com.flyfishxu.kadb.queue.AdbMessageQueue
import okio.Buffer
import okio.Pipe
import okio.Source
import okio.Timeout
import java.io.EOFException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

class AdbStreamTransferTest {
    @Test fun transportEofDoesNotReturnPartialShellOutputAsSuccess() {
        val incoming = Pipe(65536)
        val remote = AdbWriter(incoming.sink)
        val queue = AdbMessageQueue(AdbReader(incoming.source)) { incoming.cancel() }
        queue.startListening(1)
        val stream = AdbStream(queue, AdbWriter(Buffer()), 4096, 1, 10)
        try {
            remote.writeWrite(10, 1, "partial".encodeToByteArray(), 0, 7)
            remote.close()
            assertFailsWith<EOFException> { stream.source.readUtf8() }
            queue.ensureEmpty()
        } finally {
            stream.close()
            queue.close()
        }
    }

    @Test fun syncBatchesSmallSourceReadsAndPreservesFraming() {
        val toHost = Pipe(65536)
        val fromHost = Pipe(65536)
        val remoteWriter = AdbWriter(toHost.sink)
        val remoteReader = AdbReader(fromHost.source)
        val queue = AdbMessageQueue(AdbReader(toHost.source)) { toHost.cancel(); fromHost.cancel() }
        queue.startListening(1)
        val stream = AdbStream(queue, AdbWriter(fromHost.sink), 8192, 1, 10)
        val executor = Executors.newFixedThreadPool(2)
        val original = ByteArray(128 * 1024) { (it % 251).toByte() }
        val source = object : Source {
            val input = Buffer().write(original)
            override fun read(sink: Buffer, byteCount: Long) = input.read(sink, minOf(byteCount, 111))
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val received = Buffer()
        val peer = executor.submit<Int> {
            val pending = Buffer()
            var writes = 0
            while (true) {
                val packet = remoteReader.readMessage()
                if (packet.command != AdbProtocol.CMD_WRTE) continue
                writes++
                assertTrue(packet.payloadLength <= 8192)
                remoteWriter.writeOkay(10, 1)
                pending.write(packet.payload)
                while (pending.size >= 8) {
                    val header = pending.peek()
                    val id = header.readUtf8(4)
                    val size = header.readIntLe()
                    if (id == "DONE") {
                        pending.skip(8)
                        assertEquals(0L, pending.size)
                        val response = Buffer().writeUtf8("OKAY").writeIntLe(0).readByteArray()
                        remoteWriter.writeWrite(10, 1, response, 0, response.size)
                        return@submit writes
                    }
                    if (pending.size < 8L + size) break
                    pending.skip(8)
                    when (id) {
                        "SEND" -> pending.skip(size.toLong())
                        "DATA" -> {
                            assertTrue(size in 1..65536)
                            received.write(pending, size.toLong())
                        }
                        else -> error("Unexpected sync frame: $id")
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") 0
        }
        try {
            executor.submit { AdbSyncStream(stream).send(source, "/data/local/tmp/test.bin", 420, 0) }.get(3, SECONDS)
            assertTrue(peer.get(3, SECONDS) < 25, "Small reads must not each wait for an ADB ACK")
            assertContentEquals(original, received.readByteArray())
        } finally {
            queue.close()
            executor.shutdownNow()
        }
    }
}
