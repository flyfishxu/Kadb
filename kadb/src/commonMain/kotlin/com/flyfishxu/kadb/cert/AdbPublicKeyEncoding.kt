package com.flyfishxu.kadb.cert

import kotlin.io.encoding.Base64

/** ADB transmits the complete UTF-8 name followed by a NUL, not a character-count prefix. */
internal fun encodeAdbPublicKeyWithName(encodedKey: ByteArray, name: String): ByteArray =
    (Base64.encode(encodedKey) + " $name\u0000").encodeToByteArray()
