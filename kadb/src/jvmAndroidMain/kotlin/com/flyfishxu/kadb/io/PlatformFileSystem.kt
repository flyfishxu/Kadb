package com.flyfishxu.kadb.io

import okio.FileSystem

internal actual val platformFileSystem: FileSystem get() = FileSystem.SYSTEM
