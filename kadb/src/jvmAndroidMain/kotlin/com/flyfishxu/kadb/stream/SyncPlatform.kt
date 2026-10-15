package com.flyfishxu.kadb.stream

internal actual fun isWindowsHost(): Boolean =
    System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true
