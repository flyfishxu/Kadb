package com.flyfishxu.kadb.pair

import java.io.ByteArrayOutputStream

internal class ByteArrayNoThrowOutputStream(size: Int) : ByteArrayOutputStream(size) {

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun close() {}
}
