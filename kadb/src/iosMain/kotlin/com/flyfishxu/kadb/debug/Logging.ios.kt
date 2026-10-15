package com.flyfishxu.kadb.debug

import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread

internal actual val loggingEnabled: Boolean =
    NSProcessInfo.processInfo.environment["KADB_LOGGING"] == "true"

internal actual fun currentThreadName(): String =
    NSThread.currentThread.name?.takeIf { it.isNotEmpty() }
        ?: if (NSThread.isMainThread) "main" else "kadb-worker"
