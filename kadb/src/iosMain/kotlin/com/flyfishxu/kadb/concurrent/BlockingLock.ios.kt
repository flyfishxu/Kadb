@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package com.flyfishxu.kadb.concurrent

import kotlinx.cinterop.autoreleasepool
import platform.Foundation.NSCondition
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread

/** Foundation owns the native lock storage, including when a connection is collected. */
internal actual class BlockingLock actual constructor() {
    private val delegate = NSRecursiveLock()
    private var owner: NSThread? = null
    private var depth = 0

    actual fun lock() {
        delegate.lock()
        owner = NSThread.currentThread
        depth++
    }

    actual fun unlock() {
        checkHeld()
        depth--
        if (depth == 0) owner = null
        delegate.unlock()
    }

    actual fun newCondition(): BlockingCondition = BlockingCondition(this)

    internal fun checkHeld() {
        check(depth > 0 && owner == NSThread.currentThread) { "Current thread does not own the lock" }
    }

    internal fun releaseForWait(): Int {
        checkHeld()
        val previousDepth = depth
        repeat(previousDepth) { unlock() }
        return previousDepth
    }

    internal fun restoreAfterWait(previousDepth: Int) {
        repeat(previousDepth) { lock() }
    }
}

internal actual class BlockingCondition(private val lock: BlockingLock) {
    private val condition = NSCondition()

    actual fun await() {
        lock.checkHeld()
        // The condition gate stays locked until wait() atomically releases it. A signaler
        // cannot slip between releasing the parent lock and beginning to wait.
        condition.lock()
        val previousDepth = lock.releaseForWait()
        try {
            condition.wait()
        } finally {
            condition.unlock()
            lock.restoreAfterWait(previousDepth)
        }
    }

    actual fun signalAll() {
        lock.checkHeld()
        condition.lock()
        try {
            condition.broadcast()
        } finally {
            condition.unlock()
        }
    }
}

internal actual fun startReaderThread(block: () -> Unit) {
    startKadbThread("kadb-reader", block)
}

@OptIn(kotlinx.cinterop.BetaInteropApi::class)
internal fun startKadbThread(name: String, block: () -> Unit) {
    NSThread(block = { autoreleasepool(block) }).apply {
        this.name = name
        start()
    }
}
