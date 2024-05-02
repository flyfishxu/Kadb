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

import javax.security.auth.Destroyable

expect class PairingAuthCtx : Destroyable {
    val msg: ByteArray
    fun initCipher(theirMsg: ByteArray?): Boolean
    fun encrypt(input: ByteArray): ByteArray?
    fun decrypt(input: ByteArray): ByteArray?
    override fun isDestroyed(): Boolean
    override fun destroy()

    companion object


}

expect val PairingAuthCtx.Companion.GCM_IV_LENGTH: Int

expect val PairingAuthCtx.Companion.CLIENT_NAME: ByteArray
expect val PairingAuthCtx.Companion.SERVER_NAME: ByteArray

expect val PairingAuthCtx.Companion.INFO: ByteArray
expect val PairingAuthCtx.Companion.HKDF_KEY_LENGTH: Int
expect fun PairingAuthCtx.Companion.createAlice(password: ByteArray): PairingAuthCtx?