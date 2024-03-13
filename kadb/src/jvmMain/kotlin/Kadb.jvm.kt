package com.flyfishxu.kadb

import java.io.File

actual fun Kadb.readMode(file: File): Int {
    var mode = 0
    if (file.canRead()) {
        mode = mode or 400
    }
    if (file.canWrite()) {
        mode = mode or 200
    }
    if (file.canExecute()) {
        mode = mode or 100
    }
    return mode
}