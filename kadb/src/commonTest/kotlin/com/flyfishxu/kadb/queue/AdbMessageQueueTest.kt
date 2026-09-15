package com.flyfishxu.kadb.queue

import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader
import com.flyfishxu.kadb.core.AdbWriter
import com.flyfishxu.kadb.exception.AdbStreamClosed
import okio.Pipe
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

class AdbMessageQueueTest {
    @Test fun takeAfterStopListeningReportsClosedStream(): Unit = Fixture().use { f ->
        f.queue.startListening(42)
        f.queue.stopListening(42)
        assertFailsWith<AdbStreamClosed> { f.queue.take(42, AdbProtocol.CMD_OKAY) }
        f.queue.ensureEmpty()
    }

    @Test fun idleEofClosesTransportWithoutAnotherCommand(): Unit = Fixture().use { f ->
        f.writer.close()
        assertTrue(f.closed.await(2, SECONDS))
        assertFalse(f.queue.isOpen)
        assertFailsWith<EOFException> { f.queue.startListening(42) }
    }

    @Test fun eofWakesEveryWaitingStream(): Unit = Fixture().use { f ->
        val executor = Executors.newFixedThreadPool(4)
        try {
            val ready = CountDownLatch(4)
            val futures = (1..4).map { id ->
                f.queue.startListening(id)
                executor.submit<Boolean> {
                    ready.countDown()
                    assertFailsWith<EOFException> { f.queue.take(id, AdbProtocol.CMD_OKAY) }
                    true
                }
            }
            assertTrue(ready.await(2, SECONDS))
            f.writer.close()
            futures.forEach { assertTrue(it.get(2, SECONDS)) }
        } finally {
            f.close()
            executor.shutdownNow()
        }
    }

    @Test fun localCloseWakesWaitingStream(): Unit = Fixture().use { f ->
        val executor = Executors.newSingleThreadExecutor()
        try {
            f.queue.startListening(1)
            val result = executor.submit<Boolean> {
                assertFailsWith<IOException> { f.queue.take(1, AdbProtocol.CMD_OKAY) }
                true
            }
            f.queue.close()
            assertTrue(result.get(2, SECONDS))
            assertTrue(f.closed.await(2, SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun closingOneStreamPreservesOtherStreamsAndQueuedData(): Unit = Fixture().use { f ->
        f.queue.startListening(1)
        f.queue.startListening(2)
        f.writer.writeWrite(10, 1, "hello".encodeToByteArray(), 0, 5)
        f.writer.writeClose(10, 1)
        f.writer.writeOkay(20, 2)
        assertEquals("hello", f.queue.take(1, AdbProtocol.CMD_WRTE).payload.decodeToString())
        assertFailsWith<AdbStreamClosed> { f.queue.take(1, AdbProtocol.CMD_WRTE) }
        assertEquals(20, f.queue.take(2, AdbProtocol.CMD_OKAY).arg0)
        assertTrue(f.queue.isOpen)
    }

    private class Fixture : AutoCloseable {
        private val pipe = Pipe(65536)
        val closed = CountDownLatch(1)
        val writer = AdbWriter(pipe.sink)
        val queue = AdbMessageQueue(AdbReader(pipe.source)) {
            pipe.cancel()
            closed.countDown()
        }
        override fun close() = queue.close()
    }
}
