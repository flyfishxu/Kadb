package com.flyfishxu.kadb.queue

import com.flyfishxu.kadb.exception.AdbStreamClosed
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MessageQueueTest {
    @Test
    fun takeAfterStopListeningReportsClosedStream() {
        val queue = TestMessageQueue()
        queue.startListening(42)
        queue.stopListening(42)

        assertFailsWith<AdbStreamClosed> {
            queue.take(42, command = 1)
        }
    }

    private class TestMessageQueue : MessageQueue<TestMessage>() {
        override fun readMessage(): TestMessage = error("readMessage should not be called")
        override fun getLocalId(message: TestMessage): Int = message.localId
        override fun getCommand(message: TestMessage): Int = message.command
        override fun isCloseCommand(message: TestMessage): Boolean = false
    }

    private data class TestMessage(val localId: Int, val command: Int)
}
