/*
 * Copyright (c) 2021 mobile.dev inc.
 *
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

package com.flyfishxu.kadb

import android.os.Build
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object PKCS8 {

    private const val PREFIX = "-----BEGIN PRIVATE KEY-----"
    private const val SUFFIX = "-----END PRIVATE KEY-----"

    fun parse(bytes: ByteArray): PrivateKey {
        val string = String(bytes).replace(PREFIX, "").replace(SUFFIX, "").replace("\n", "")
        val encoded = encoding(string)
        val keyFactory = KeyFactory.getInstance("RSA")
        val keySpec = PKCS8EncodedKeySpec(encoded)
        return keyFactory.generatePrivate(keySpec)
    }

    private fun encoding(string: String): ByteArray {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getDecoder().decode(string)
        } else {
            android.util.Base64.decode(string, android.util.Base64.DEFAULT)
        }
    }
}