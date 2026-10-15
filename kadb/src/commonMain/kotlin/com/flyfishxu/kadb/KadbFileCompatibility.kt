package com.flyfishxu.kadb

/** Platform compatibility overloads; new multiplatform callers use Okio Path/Source/Sink. */
abstract expect class KadbFileCompatibility internal constructor() {
    protected abstract val fileClient: Kadb
}
