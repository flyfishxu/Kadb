package com.flyfishxu.kadb

import java.util.Base64

actual fun PKCS8.encoding(string: String): ByteArray {
    return Base64.getDecoder().decode(string)
}