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

import java.io.UnsupportedEncodingException
import java.nio.charset.IllegalCharsetNameException
import javax.security.auth.Destroyable

internal expect class PairingAuthCtx : Destroyable {
    val msg: ByteArray
    fun initCipher(theirMsg: ByteArray?): Boolean
    fun encrypt(input: ByteArray): ByteArray?
    fun decrypt(input: ByteArray): ByteArray?
    override fun isDestroyed(): Boolean
    override fun destroy()

    companion object
}

internal fun getBytes(text: String, charsetName: String): ByteArray {
    return try {
        text.toByteArray(charset(charsetName))
    } catch (e: UnsupportedEncodingException) {
        throw (IllegalCharsetNameException("Illegal charset $charsetName").initCause(e) as IllegalCharsetNameException)
    }
}

internal expect fun PairingAuthCtx.Companion.createAlice(password: ByteArray): PairingAuthCtx?
