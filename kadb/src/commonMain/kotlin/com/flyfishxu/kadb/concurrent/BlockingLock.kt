package com.flyfishxu.kadb.concurrent

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Blocking, reentrant lock for the synchronous API. Never hold across suspension. */
internal expect class BlockingLock() {
    fun lock()
    fun unlock()
    fun newCondition(): BlockingCondition
}

internal expect class BlockingCondition {
    fun await()
    fun signalAll()
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T> BlockingLock.withLock(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

internal expect fun startReaderThread(block: () -> Unit)
