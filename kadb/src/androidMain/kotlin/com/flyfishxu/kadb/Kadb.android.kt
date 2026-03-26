package com.flyfishxu.kadb

import android.content.Context
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import okio.sink
import okio.source
import java.io.File
import java.nio.file.Files

fun Kadb.pull(
    dst: DocumentFile,
    remotePath: String,
    context: Context
) {
    val outputStream = context.contentResolver.openOutputStream(dst.uri)
    checkNotNull(outputStream)
    outputStream.use { stream ->
        stream.sink().use { sink ->
            pull(sink, remotePath)
        }
    }
}

fun Kadb.push(
    src: DocumentFile,
    remotePath: String,
    context: Context,
    mode: Int = readMode(src),
    lastModifiedMs: Long = src.lastModified()
) {
    val inputStream = context.contentResolver.openInputStream(src.uri)
    checkNotNull(inputStream)
    inputStream.use { stream ->
        stream.source().use { source ->
            push(source, remotePath, mode, lastModifiedMs)
        }
    }
}

fun Kadb.install(
    src: DocumentFile,
    context: Context
) {
    val inputStream = context.contentResolver.openInputStream(src.uri)
    checkNotNull(inputStream)
    inputStream.use { stream ->
        stream.source().use { source ->
            install(source, src.length())
        }
    }
}

fun Kadb.readMode(file: DocumentFile): Int {
    // SYNC SEND uses Unix mode bits (e.g. 0o400) encoded as decimal.
    // https://android.googlesource.com/platform/system/core/+/refs/tags/android-11.0.0_r20/adb/SYNC.TXT
    var mode = 0
    if (file.canRead()) {
        mode = mode or 256
    }
    if (file.canWrite()) {
        mode = mode or 128
    }
    return mode
}

actual fun Kadb.readMode(file: File): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Files.getAttribute(file.toPath(), "unix:mode") as? Int ?: throw RuntimeException(
            "Unable to read file mode"
        )
    } else {
        var mode = 0
        if (file.canRead()) {
            mode = mode or 256
        }
        if (file.canWrite()) {
            mode = mode or 128
        }
        if (file.canExecute()) {
            mode = mode or 64
        }
        mode
    }
}
