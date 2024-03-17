package com.flyfishxu.kadb

import kotlin.io.encoding.ExperimentalEncodingApi


@OptIn(ExperimentalEncodingApi::class)
actual fun PKCS8.encoding(string: String): ByteArray {
    return string.encodeToByteArray()
}