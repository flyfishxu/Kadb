@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.flyfishxu.kadb.concurrent

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

class BlockingLockTest {
    @Test
    fun conditionReleasesAndRestoresAllReentrantAcquisitions() {
        val lock = BlockingLock()
        val condition = lock.newCondition()
        val waiting = dispatch_semaphore_create(0)
        val completed = dispatch_semaphore_create(0)
        val failure = AtomicReference<Throwable?>(null)
        var signalled = false

        startKadbThread("kadb-condition-test-waiter") {
            try {
                lock.lock()
                lock.lock()
                try {
                    dispatch_semaphore_signal(waiting)
                    while (!signalled) condition.await()
                    lock.unlock()
                    // One recursive acquisition must remain after the first unlock.
                    condition.signalAll()
                } finally {
                    lock.unlock()
                }
            } catch (error: Throwable) {
                failure.store(error)
            } finally {
                dispatch_semaphore_signal(completed)
            }
        }

        assertEquals(0L, dispatch_semaphore_wait(waiting, dispatch_time(DISPATCH_TIME_NOW, 5_000_000_000)))
        startKadbThread("kadb-condition-test-signaler") {
            try {
                lock.withLock {
                    signalled = true
                    condition.signalAll()
                }
            } catch (error: Throwable) {
                failure.store(error)
            }
        }
        assertEquals(0L, dispatch_semaphore_wait(completed, dispatch_time(DISPATCH_TIME_NOW, 5_000_000_000)))
        assertNull(failure.load())
    }
}
