package com.flyfishxu.kadb.pair

import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

internal actual class PairingCrypto actual constructor(myName: ByteArray, theirName: ByteArray) {
    private val spake = Spake2Context(Spake2Role.Alice, myName, theirName)

    actual fun generateMessage(password: ByteArray): ByteArray = spake.generateMessage(password)
    actual fun processMessage(message: ByteArray?): ByteArray? = spake.processMessage(message)

    actual fun deriveKey(material: ByteArray, info: ByteArray): ByteArray = ByteArray(HKDF_KEY_LENGTH).also {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(material, null, info))
        hkdf.generateBytes(it, 0, it.size)
    }

    actual fun encryptDecrypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray? {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(encrypt, AEADParameters(KeyParameter(key), key.size * 8, nonce))
        val output = ByteArray(cipher.getOutputSize(input.size))
        val offset = cipher.processBytes(input, 0, input.size, output, 0)
        return try {
            cipher.doFinal(output, offset)
            output
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    actual fun destroy() = spake.destroy()
}
