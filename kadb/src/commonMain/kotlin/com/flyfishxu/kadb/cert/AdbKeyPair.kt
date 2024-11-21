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
package com.flyfishxu.kadb.cert

import com.flyfishxu.kadb.core.AdbMessage
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import javax.crypto.Cipher

internal class AdbKeyPair(
    val privateKey: PrivateKey, val publicKey: PublicKey, val certificate: Certificate
) {
    internal fun signPayload(message: AdbMessage): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(AndroidPubkey.SIGNATURE_PADDING)
        return cipher.doFinal(message.payload, 0, message.payloadLength)
    }
}