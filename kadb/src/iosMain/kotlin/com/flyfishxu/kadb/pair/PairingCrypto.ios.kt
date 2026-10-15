@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flyfishxu.kadb.pair

import cnames.structs.kadb_spake
import com.flyfishxu.kadb.cert.withNativeBytes
import com.flyfishxu.kadb.nativebridge.*
import kotlinx.cinterop.*
import platform.posix.size_tVar

internal actual class PairingCrypto actual constructor(myName: ByteArray, theirName: ByteArray) {
    private var context: CPointer<kadb_spake>? = myName.withNativeBytes { me, myLength ->
        theirName.withNativeBytes { peer, peerLength -> kadb_spake_new(1, me, myLength, peer, peerLength) }
    }.also { checkNotNull(it) { "Failed to create SPAKE2 context" } }

    actual fun generateMessage(password: ByteArray): ByteArray = ByteArray(32).also { message ->
        val success = password.withNativeBytes { bytes, length ->
            message.withNativeBytes { output, _ -> kadb_spake_generate(checkNotNull(context), bytes, length, output) }
        }
        check(success == 1) { "Failed to generate SPAKE2 message" }
    }

    actual fun processMessage(message: ByteArray?): ByteArray? {
        if (message == null || context == null) return null
        val material = ByteArray(64)
        val success = message.withNativeBytes { bytes, length ->
            material.withNativeBytes { output, _ -> kadb_spake_process(context, bytes, length, output) }
        }
        return if (success == 1) material else { material.fill(0); null }
    }

    actual fun deriveKey(material: ByteArray, info: ByteArray): ByteArray = ByteArray(HKDF_KEY_LENGTH).also { key ->
        val success = material.withNativeBytes { bytes, length ->
            info.withNativeBytes { infoBytes, infoLength ->
                key.withNativeBytes { output, _ -> kadb_hkdf_sha256(bytes, length, infoBytes, infoLength, output) }
            }
        }
        check(success == 1) { "Failed to derive pairing key" }
    }

    actual fun encryptDecrypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray? {
        if ((!encrypt && input.size < 16) || input.size > Int.MAX_VALUE - 16) return null
        // Keep a non-null output buffer for authenticated empty plaintexts.
        val output = ByteArray(if (encrypt) input.size + 16 else maxOf(1, input.size - 16))
        return memScoped {
            val written = alloc<size_tVar>()
            val success = key.withNativeBytes { keyBytes, _ ->
                nonce.withNativeBytes { iv, _ ->
                    input.withNativeBytes { bytes, length ->
                        output.withNativeBytes { result, capacity ->
                            kadb_aes_gcm(if (encrypt) 1 else 0, keyBytes, iv, bytes, length, result, capacity, written.ptr)
                        }
                    }
                }
            }
            if (success == 1) output.copyOf(written.value.toInt()) else null
        }
    }

    actual fun destroy() {
        kadb_spake_free(context)
        context = null
    }
}
