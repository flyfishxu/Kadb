package com.flyfishxu.kadb

import android.os.Build
import java.util.Base64

actual fun PKCS8.encoding(string: String): ByteArray {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Base64.getDecoder().decode(string)
    } else {
        android.util.Base64.decode(string, android.util.Base64.DEFAULT)
    }
}
