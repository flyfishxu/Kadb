package com.flyfishxu.kadb.queue

import com.flyfishxu.kadb.exception.AdbStreamClosed
import org.jetbrains.annotations.TestOnly
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

internal abstract class MessageQueue<V> {

    private val readLock = ReentrantLock()
    private val queueLock = ReentrantLock()
    private val queueCond = queueLock.newCondition()
    private val queues = ConcurrentHashMap<Int, ConcurrentHashMap<Int, Queue<V>>>()

    //private val openStreams = ConcurrentHashMap<Int, Boolean>().keySet(true)
    private val openStreams = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    fun take(localId: Int, command: Int): V {
        while (true) {
            queueLock.lock {
                poll(localId, command)?.let { return it }
                readLock.tryLock({
                    queueLock.unlock()
                    read()
                    queueLock.lock()
                    queueCond.signalAll()
                }) { queueCond.await() }
            }
        }
    }

    fun startListening(localId: Int) {
        openStreams.add(localId)
        queues.putIfAbsent(localId, ConcurrentHashMap())
    }

    fun stopListening(localId: Int) {
        openStreams.remove(localId)
        queues.remove(localId)
    }

    @TestOnly
    fun ensureEmpty() {
        check(queues.isEmpty()) {
            "Queues is not empty: ${
                queues.keys.map {
                    String.format(
                        "%X", it
                    )
                }
            }"
        }
        check(openStreams.isEmpty())
    }

    protected abstract fun readMessage(): V

    protected abstract fun getLocalId(message: V): Int

    protected abstract fun getCommand(message: V): Int

    protected abstract fun isCloseCommand(message: V): Boolean

    private fun poll(localId: Int, command: Int): V? {
        val streamQueues = queues[localId] ?: throw IllegalStateException("Not listening for localId: $localId")
        val message = streamQueues[command]?.poll()
        if (message == null && !openStreams.contains(localId)) {
            throw AdbStreamClosed(localId)
        }
        return message
    }

    private fun read() {
        val message = readMessage()
        val localId = getLocalId(message)

        if (isCloseCommand(message)) {
            openStreams.remove(localId)
            return
        }

        val streamQueues = queues[localId] ?: return

        val command = getCommand(message)
        //val commandQueue = streamQueues.computeIfAbsent(command) { ConcurrentLinkedQueue() }
        val commandQueue = synchronized(streamQueues) {
            val existingQueue = streamQueues[command]
            if (existingQueue != null) {
                existingQueue
            } else {
                val newQueue: Queue<V> = ConcurrentLinkedQueue()
                streamQueues[command] = newQueue
                newQueue
            }
        }
        commandQueue.add(message)
    }
}

private inline fun <T> ReentrantLock.lock(body: () -> T) {
    lock()
    try {
        body()
    } finally {
        if (isHeldByCurrentThread) unlock()
    }
}

private inline fun ReentrantLock.tryLock(body: () -> Unit, elseBody: () -> Unit) {
    return if (tryLock()) {
        try {
            body()
        } finally {
            if (isHeldByCurrentThread) unlock()
        }
    } else {
        elseBody()
    }
}
