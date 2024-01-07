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

import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import javax.security.auth.Destroyable

internal class PairingAuthCtx private constructor(
    private val mSpake2Ctx: Spake2Context,
    password: ByteArray
) : Destroyable {
    val msg: ByteArray
    private val mSecretKey = ByteArray(HKDF_KEY_LENGTH)
    private var mDecIv: Long = 0
    private var mEncIv: Long = 0
    private var mIsDestroyed = false

    init {
        msg = mSpake2Ctx.generateMessage(password)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun initCipher(theirMsg: ByteArray?): Boolean {
        if (mIsDestroyed) return false
        val keyMaterial = mSpake2Ctx.processMessage(theirMsg) ?: return false
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(keyMaterial, null, INFO))
        hkdf.generateBytes(mSecretKey, 0, mSecretKey.size)
        return true
    }

    fun encrypt(`in`: ByteArray): ByteArray? {
        return encryptDecrypt(
            true, `in`, ByteBuffer.allocate(GCM_IV_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(mEncIv++).array()
        )
    }

    fun decrypt(`in`: ByteArray): ByteArray? {
        return encryptDecrypt(
            false, `in`, ByteBuffer.allocate(GCM_IV_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(mDecIv++).array()
        )
    }

    override fun isDestroyed(): Boolean {
        return mIsDestroyed
    }

    override fun destroy() {
        mIsDestroyed = true
        Arrays.fill(mSecretKey, 0.toByte())
        mSpake2Ctx.destroy()
    }

    private fun encryptDecrypt(forEncryption: Boolean, `in`: ByteArray, iv: ByteArray): ByteArray? {
        if (mIsDestroyed) return null
        val spec = AEADParameters(KeyParameter(mSecretKey), mSecretKey.size * 8, iv)
        val cipher = GCMBlockCipher(AESEngine())
        cipher.init(forEncryption, spec)
        val out = ByteArray(cipher.getOutputSize(`in`.size))
        val newOffset = cipher.processBytes(`in`, 0, `in`.size, out, 0)
        try {
            cipher.doFinal(out, newOffset)
        } catch (e: InvalidCipherTextException) {
            return null
        }
        return out
    }

    companion object {
        const val GCM_IV_LENGTH = 12 // in bytes

        // The following values are taken from the following source and are subjected to change
        // https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_auth/pairing_auth.cpp
        private val CLIENT_NAME = StringCompat.getBytes("adb pair client\u0000", "UTF-8")
        private val SERVER_NAME = StringCompat.getBytes("adb pair server\u0000", "UTF-8")

        // The following values are taken from the following source and are subjected to change
        // https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_auth/aes_128_gcm.cpp
        private val INFO = StringCompat.getBytes("adb pairing_auth aes-128-gcm key", "UTF-8")
        private const val HKDF_KEY_LENGTH = 128 / 8

        @JvmStatic
        fun createAlice(password: ByteArray): PairingAuthCtx? {
            val spake25519 = Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME)
            return try {
                PairingAuthCtx(spake25519, password)
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: IllegalStateException) {
                null
            }
        }

    }
}
