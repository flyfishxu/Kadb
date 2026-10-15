package com.flyfishxu.kadb.debug

internal actual val loggingEnabled: Boolean = "true" == System.getenv("KADB_LOGGING")
internal actual fun currentThreadName(): String = Thread.currentThread().name
