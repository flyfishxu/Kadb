/*
 * Copyright (c) 2021 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.flyfishxu.kadb.queue

import com.flyfishxu.kadb.core.AdbMessage
import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader
import com.flyfishxu.kadb.exception.AdbStreamClosed
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** One transport reader detects EOF even when all streams are idle. */
internal class AdbMessageQueue(
    private val reader: AdbReader,
    private val closeTransport: () -> Unit,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queues = mutableMapOf<Int, MutableMap<Int, ArrayDeque<AdbMessage>>>()
    private val openStreams = mutableSetOf<Int>()
    @Volatile private var failure: Throwable? = null
    val isOpen: Boolean get() = failure == null

    init {
        thread(name = "kadb-reader", isDaemon = true) {
            try {
                while (isOpen) dispatch(reader.readMessage())
            } catch (error: Throwable) {
                terminate(error)
            } finally {
                runCatching { reader.close() }
            }
        }
    }

    fun take(localId: Int, command: Int): AdbMessage = lock.withLock {
        while (true) {
            val stream = queues[localId] ?: throw AdbStreamClosed(localId)
            stream[command]?.poll()?.let { return it }
            if (localId !in openStreams) throw AdbStreamClosed(localId)
            failure?.let { throw it }
            changed.await()
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    fun startListening(localId: Int) = lock.withLock {
        failure?.let { throw it }
        openStreams.add(localId)
        queues.getOrPut(localId) { mutableMapOf() }
        Unit
    }

    fun stopListening(localId: Int) = lock.withLock {
        openStreams.remove(localId)
        queues.remove(localId)
        changed.signalAll()
    }

    private fun dispatch(message: AdbMessage) = lock.withLock {
        val localId = message.arg1
        if (message.command == AdbProtocol.CMD_CLSE) {
            openStreams.remove(localId)
        } else {
            queues[localId]?.getOrPut(message.command) { ArrayDeque() }?.add(message)
        }
        changed.signalAll()
    }

    private fun terminate(error: Throwable) {
        lock.withLock {
            if (failure != null) return
            failure = error
            changed.signalAll()
        }
        // Close the socket before any buffered reader/writer: unblock pending I/O first.
        runCatching { closeTransport() }
    }

    override fun close() = terminate(IOException("ADB connection closed"))

    @TestOnly
    fun ensureEmpty() = lock.withLock {
        check(queues.isEmpty() && openStreams.isEmpty()) { "ADB streams still open: ${queues.keys}" }
    }
}
