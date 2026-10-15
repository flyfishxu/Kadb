@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flyfishxu.kadb

import kotlinx.cinterop.*
import okio.IOException
import okio.Path
import platform.posix.stat
import platform.posix.strerror
import platform.posix.errno

actual abstract class KadbFileCompatibility actual internal constructor() {
    protected actual abstract val fileClient: Kadb
}

internal actual fun Kadb.readMode(file: Path): Int = memScoped {
    val info = alloc<stat>()
    if (stat(file.toString(), info.ptr) != 0) throw IOException("Unable to read file mode: ${strerror(errno)?.toKString()}")
    info.st_mode.toInt()
}
