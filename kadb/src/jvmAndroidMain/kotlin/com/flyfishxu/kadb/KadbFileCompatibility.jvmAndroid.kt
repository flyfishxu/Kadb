package com.flyfishxu.kadb

import java.io.File
import okio.Path.Companion.toOkioPath
import okio.sink
import okio.source

/** Keeps existing File calls source-compatible while common APIs use Okio paths. */
actual abstract class KadbFileCompatibility actual internal constructor() {
    protected actual abstract val fileClient: Kadb

    fun push(src: File, remotePath: String, mode: Int = fileClient.readMode(src), lastModifiedMs: Long = src.lastModified()) =
        src.source().use { fileClient.push(it, remotePath, mode, lastModifiedMs) }

    fun pull(dst: File, remotePath: String) = dst.sink(false).use { fileClient.pull(it, remotePath) }

    fun install(file: File, vararg options: String) = fileClient.install(file.toOkioPath(), *options)

    fun installMultiple(apks: List<File>, vararg options: String) =
        fileClient.installMultiple(apks.map { it.toOkioPath() }, *options)

    companion object {
        /** Resolves the original Kadb.push$default descriptor for already compiled Kotlin callers. */
        @JvmStatic
        @JvmSynthetic
        @Suppress("UNUSED_PARAMETER")
        fun `push$default`(
            client: Kadb,
            src: File,
            remotePath: String,
            mode: Int,
            lastModifiedMs: Long,
            mask: Int,
            marker: Any?,
        ) {
            val resolvedMode = if (mask and 4 != 0) client.readMode(src) else mode
            val resolvedTimestamp = if (mask and 8 != 0) src.lastModified() else lastModifiedMs
            client.push(src, remotePath, resolvedMode, resolvedTimestamp)
        }
    }
}

internal expect fun Kadb.readMode(file: File): Int
