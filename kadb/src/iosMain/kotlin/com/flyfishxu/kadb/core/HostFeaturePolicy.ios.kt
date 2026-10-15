package com.flyfishxu.kadb.core

import platform.Foundation.NSProcessInfo

internal actual fun aospDefaultDelayedAckEnabled(): Boolean =
    NSProcessInfo.processInfo.environment["ADB_BURST_MODE"] == "1"
