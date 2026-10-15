/*
 * Copyright (c) 2024 Flyfish-Xu
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.flyfishxu.kadb.pair

import okio.Buffer

// Protocol labels include the terminating zero where AOSP uses sizeof(string literal).
private val CLIENT_NAME get() = "adb pair client\u0000".encodeToByteArray()
private val SERVER_NAME get() = "adb pair server\u0000".encodeToByteArray()
internal val INFO get() = "adb pairing_auth aes-128-gcm key".encodeToByteArray()
internal const val HKDF_KEY_LENGTH = 16

/** All pairing protocol state and wire-format decisions are shared across platforms. */
internal class PairingAuthCtx private constructor(private val crypto: PairingCrypto, password: ByteArray) {
    val msg: ByteArray = crypto.generateMessage(password)
    private val secretKey = ByteArray(HKDF_KEY_LENGTH)
    private var decryptCounter = 0L
    private var encryptCounter = 0L
    private var destroyed = false

    fun initCipher(theirMsg: ByteArray?): Boolean {
        if (destroyed) return false
        val material = crypto.processMessage(theirMsg) ?: return false
        return try {
            val key = crypto.deriveKey(material, INFO)
            try { key.copyInto(secretKey) } finally { key.fill(0) }
            true
        } finally {
            material.fill(0)
        }
    }

    fun encrypt(input: ByteArray): ByteArray? = crypt(true, input, encryptCounter++)
    fun decrypt(input: ByteArray): ByteArray? = crypt(false, input, decryptCounter++)
    fun isDestroyed(): Boolean = destroyed

    fun destroy() {
        if (destroyed) return
        destroyed = true
        secretKey.fill(0)
        crypto.destroy()
    }

    private fun crypt(encrypt: Boolean, input: ByteArray, counter: Long): ByteArray? {
        if (destroyed) return null
        val nonce = Buffer().writeLongLe(counter).writeIntLe(0).readByteArray()
        return crypto.encryptDecrypt(encrypt, secretKey, nonce, input)
    }

    companion object {
        internal fun create(password: ByteArray): PairingAuthCtx? {
            val crypto = PairingCrypto(CLIENT_NAME, SERVER_NAME)
            return try {
                PairingAuthCtx(crypto, password)
            } catch (_: IllegalArgumentException) {
                crypto.destroy()
                null
            } catch (_: IllegalStateException) {
                crypto.destroy()
                null
            }
        }
    }
}

internal fun PairingAuthCtx.Companion.createAlice(password: ByteArray): PairingAuthCtx? = create(password)

/** Only the cryptographic primitives require a platform implementation. */
internal expect class PairingCrypto(myName: ByteArray, theirName: ByteArray) {
    fun generateMessage(password: ByteArray): ByteArray
    fun processMessage(message: ByteArray?): ByteArray?
    fun deriveKey(material: ByteArray, info: ByteArray): ByteArray
    fun encryptDecrypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray?
    fun destroy()
}
