package com.flyfishxu.kadb

import java.io.File
import java.nio.file.Files

actual fun Kadb.readMode(file: File): Int {
    return Files.getAttribute(
        file.toPath(), "unix:mode"
    ) as? Int ?: throw RuntimeException("Unable to read file mode")
}