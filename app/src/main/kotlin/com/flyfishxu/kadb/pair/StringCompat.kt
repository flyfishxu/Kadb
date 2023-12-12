package com.flyfishxu.kadb.pair

import java.io.UnsupportedEncodingException
import java.nio.charset.IllegalCharsetNameException

internal object StringCompat {
    fun getBytes(text: String, charsetName: String): ByteArray {
        return try {
            text.toByteArray(charset(charsetName))
        } catch (e: UnsupportedEncodingException) {
            throw (IllegalCharsetNameException("Illegal charset $charsetName")
                .initCause(e) as IllegalCharsetNameException)
        }
    }
}
