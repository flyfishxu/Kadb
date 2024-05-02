package com.flyfishxu.kadb.pair

import javax.security.auth.Destroyable

actual class PairingAuthCtx : Destroyable {
    actual fun initCipher(theirMsg: ByteArray?): Boolean {
        TODO("Not yet implemented")
    }

    actual val msg: ByteArray
        get() = TODO("Not yet implemented")

    actual fun encrypt(input: ByteArray): ByteArray? {
        TODO("Not yet implemented")
    }

    actual fun decrypt(input: ByteArray): ByteArray? {
        TODO("Not yet implemented")
    }

    actual override fun isDestroyed(): Boolean {
        TODO("Not yet implemented")
    }

    actual override fun destroy() {
    }

    actual companion object

}

actual val PairingAuthCtx.Companion.GCM_IV_LENGTH: Int
    get() = TODO("Not yet implemented")
actual val PairingAuthCtx.Companion.CLIENT_NAME: ByteArray
    get() = TODO("Not yet implemented")
actual val PairingAuthCtx.Companion.SERVER_NAME: ByteArray
    get() = TODO("Not yet implemented")
actual val PairingAuthCtx.Companion.INFO: ByteArray
    get() = TODO("Not yet implemented")
actual val PairingAuthCtx.Companion.HKDF_KEY_LENGTH: Int
    get() = TODO("Not yet implemented")

actual fun PairingAuthCtx.Companion.createAlice(password: ByteArray): PairingAuthCtx? {
    TODO("Not yet implemented")
}