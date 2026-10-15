package com.flyfishxu.kadb.cert

import kotlin.io.encoding.Base64

/** The persisted identity format is identical on every platform. */
internal fun decodeIdentityPem(pem: ByteArray, label: String): ByteArray = Base64.decode(
    pem.decodeToString()
        .replace("-----BEGIN $label-----", "")
        .replace("-----END $label-----", "")
        .replace("\r", "").replace("\n", "").trim()
)

internal fun encodeIdentityPem(der: ByteArray, label: String): ByteArray =
    "-----BEGIN $label-----\n${Base64.encode(der)}\n-----END $label-----".encodeToByteArray()
