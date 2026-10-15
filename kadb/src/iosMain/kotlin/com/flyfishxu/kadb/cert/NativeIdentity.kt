@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flyfishxu.kadb.cert

import cnames.structs.kadb_identity
import com.flyfishxu.kadb.nativebridge.*
import kotlinx.cinterop.*
import platform.posix.size_tVar

internal inline fun <T> ByteArray.withNativeBytes(block: (CPointer<UByteVar>?, ULong) -> T): T =
    usePinned { block(if (isEmpty()) null else it.addressOf(0).reinterpret(), size.toULong()) }

internal inline fun <T> withNativeIdentity(privateDer: ByteArray, block: (CPointer<kadb_identity>) -> T): T {
    val identity = privateDer.withNativeBytes { bytes, length -> kadb_identity_import(bytes, length) }
        ?: throw KadbCertException.PrivateKeyParseFailed()
    return try { block(identity) } finally { kadb_identity_free(identity) }
}

internal fun readNativeBlob(read: (CPointer<CPointerVar<UByteVar>>, CPointer<size_tVar>) -> Int): ByteArray =
    memScoped {
        val output = alloc<CPointerVar<UByteVar>>()
        val length = alloc<size_tVar>()
        output.value = null
        length.value = 0u
        try {
            check(read(output.ptr, length.ptr) == 1) { "Failed to encode native identity" }
            require(length.value <= Int.MAX_VALUE.toULong())
            output.value!!.reinterpret<ByteVar>().readBytes(length.value.toInt())
        } finally {
            kadb_crypto_free(output.value, length.value)
        }
    }
