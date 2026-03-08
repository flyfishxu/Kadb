package com.flyfishxu.kadb.core

import com.flyfishxu.kadb.DelayedAckMode

internal fun shouldAdvertiseDelayedAck(mode: DelayedAckMode): Boolean = when (mode) {
    DelayedAckMode.AOSP_DEFAULT -> aospDefaultDelayedAckEnabled()
    DelayedAckMode.ENABLED -> true
    DelayedAckMode.DISABLED -> false
}

internal expect fun aospDefaultDelayedAckEnabled(): Boolean
