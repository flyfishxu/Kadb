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
    val sink = outputStream.sink()
    pull(sink, remotePath)
    outputStream.close()
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
    val source = inputStream.source()
    push(source, remotePath, mode, lastModifiedMs)
    inputStream.close()
}

fun Kadb.install(
    src: DocumentFile,
    context: Context
) {
    val inputStream = context.contentResolver.openInputStream(src.uri)
    checkNotNull(inputStream)
    val source = inputStream.source()
    install(source, src.length())
}

fun Kadb.readMode(file: DocumentFile): Int {
    return if (file.canRead()) 400
    else if (file.canWrite()) 200
    else 0
}

actual fun Kadb.readMode(file: File): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Files.getAttribute(file.toPath(), "unix:mode") as? Int ?: throw RuntimeException(
            "Unable to read file mode"
        )
    } else {
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
        mode
    }
}