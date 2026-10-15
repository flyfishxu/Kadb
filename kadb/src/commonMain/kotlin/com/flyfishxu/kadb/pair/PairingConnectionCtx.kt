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

import com.flyfishxu.kadb.cert.AdbKeyPair
import okio.Buffer
import okio.Closeable
import okio.IOException

internal class PairingConnectionCtx(
    host: String, port: Int, pwd: ByteArray, keyPair: AdbKeyPair, deviceName: String
) : Closeable {
    private val mPwd = pwd
    private val mPeerInfo = PeerInfo(PeerInfo.ADB_RSA_PUB_KEY, keyPair.encodeAdbPublicKey(deviceName))
    private val transport = PairingTlsConnection(host, port, keyPair)
    private var mPairingAuthCtx: PairingAuthCtx? = null
    private var mState = State.Ready

    @Throws(IOException::class)
    fun start() {
        if (mState != State.Ready) {
            throw IOException("Connection is not ready yet.")
        }
        mState = State.ExchangingMsgs

        // Start worker
        setupTlsConnection()
        while (true) {
            mState = when (mState) {
                State.ExchangingMsgs -> {
                    if (!doExchangeMsgs()) {
                        notifyResult()
                        throw IOException("Exchanging message wasn't successful.")
                    }
                    State.ExchangingPeerInfo
                }

                State.ExchangingPeerInfo -> {
                    if (!doExchangePeerInfo()) {
                        notifyResult()
                        throw IOException("Could not exchange peer info.")
                    }
                    notifyResult()
                    return
                }

                State.Ready, State.Stopped -> throw IOException(
                    "Connection closed with errors."
                )
            }
        }
    }

    private fun notifyResult() {
        mState = State.Stopped
    }

    @Throws(IOException::class)
    private fun setupTlsConnection() {
        transport.start()
        // Bind the PAKE password to this TLS session, as required by ADB.
        val keyMaterial = transport.exportKeyingMaterial()
        val passwordBytes = ByteArray(mPwd.size + keyMaterial.size)
        mPwd.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, mPwd.size)
        val pairingAuthCtx =
            PairingAuthCtx.createAlice(passwordBytes) ?: throw IOException("Unable to create PairingAuthCtx.")
        mPairingAuthCtx = pairingAuthCtx
    }

    @Throws(IOException::class)
    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = Buffer()
        header.writeTo(buffer)
        transport.write(buffer.readByteArray())
        transport.write(payload)
    }

    @Throws(IOException::class)
    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(PairingPacketHeader.PAIRING_PACKET_HEADER_SIZE.toInt())
        transport.readFully(bytes)
        val buffer = Buffer().write(bytes)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun createHeader(type: Byte, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(
            PairingPacketHeader.CURRENT_KEY_HEADER_VERSION, type, payloadSize
        )
    }

    private fun checkHeaderType(expected: Byte, actual: Byte): Boolean {
        return expected == actual
    }

    @Throws(IOException::class)
    private fun doExchangeMsgs(): Boolean {
        val msg = mPairingAuthCtx!!.msg
        val ourHeader = createHeader(PairingPacketHeader.SPAKE2_MSG, msg.size)
        // Write our SPAKE2 msg
        writeHeader(ourHeader, msg)

        // Read the peer's SPAKE2 msg header
        val theirHeader = readHeader()
        if (theirHeader == null || !checkHeaderType(
                PairingPacketHeader.SPAKE2_MSG, theirHeader.type
            )
        ) return false

        // Read the SPAKE2 msg payload and initialize the cipher for encrypting the PeerInfo and certificate.
        val theirMsg = ByteArray(theirHeader.payloadSize)
        transport.readFully(theirMsg)
        return try {
            mPairingAuthCtx!!.initCipher(theirMsg)
        } catch (e: Exception) {
            throw IOException(null, e)
        }
    }

    @Throws(IOException::class)
    private fun doExchangePeerInfo(): Boolean {
        // Encrypt PeerInfo
        val buffer = Buffer()
        mPeerInfo.writeTo(buffer)
        val outBuffer = mPairingAuthCtx!!.encrypt(buffer.readByteArray()) ?: return false
        // Write out the packet header
        val ourHeader = createHeader(PairingPacketHeader.PEER_INFO, outBuffer.size)
        // Write out the encrypted payload
        writeHeader(ourHeader, outBuffer)

        // Read in the peer's packet header
        val theirHeader = readHeader()
        if (theirHeader == null || !checkHeaderType(
                PairingPacketHeader.PEER_INFO, theirHeader.type
            )
        ) return false

        // Read in the encrypted peer certificate
        val theirMsg = ByteArray(theirHeader.payloadSize)
        transport.readFully(theirMsg)

        // Try to decrypt the certificate
        val decryptedMsg = mPairingAuthCtx!!.decrypt(theirMsg) ?: return false

        // The decrypted message should contain the PeerInfo.
        if (decryptedMsg.size != PeerInfo.MAX_PEER_INFO_SIZE) {
            return false
        }
        PeerInfo.readFrom(Buffer().write(decryptedMsg))
        return true
    }

    override fun close() {
        mPwd.fill(0)
        transport.close()
        if (mState != State.Ready) {
            mPairingAuthCtx?.destroy()
        }
    }

    private enum class State {
        Ready, ExchangingMsgs, ExchangingPeerInfo, Stopped
    }

    private class PeerInfo(private val type: Byte, data: ByteArray) {
        private val data = ByteArray(MAX_PEER_INFO_SIZE - 1)

        init {
            data.copyInto(this.data, endIndex = data.size.coerceAtMost(MAX_PEER_INFO_SIZE - 1))
        }

        fun writeTo(buffer: Buffer) {
            buffer.writeByte(type.toInt()).write(data)
        }

        override fun toString(): String {
            return "PeerInfo{" + "type=" + type + ", data=" + data.contentToString() + '}'
        }

        companion object {
            const val MAX_PEER_INFO_SIZE = 1 shl 13
            const val ADB_RSA_PUB_KEY: Byte = 0
            // AOSP pairing PeerInfoType uses 1 for ADB_DEVICE_GUID.
            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/pairing_connection/include/adb/pairing/pairing_connection.h#50
            const val ADB_DEVICE_GUID: Byte = 1
            fun readFrom(buffer: Buffer): PeerInfo {
                val type = buffer.readByte()
                val data = ByteArray(MAX_PEER_INFO_SIZE - 1)
                buffer.readFully(data)
                return PeerInfo(type, data)
            }
        }
    }

    private class PairingPacketHeader(
        private val version: Byte, val type: Byte, val payloadSize: Int
    ) {
        fun writeTo(buffer: Buffer) {
            buffer.writeByte(version.toInt()).writeByte(type.toInt()).writeInt(payloadSize)
        }

        override fun toString(): String {
            return "PairingPacketHeader{version=$version, type=$type, payloadSize=$payloadSize}"
        }

        companion object {
            const val CURRENT_KEY_HEADER_VERSION: Byte = 1
            const val MIN_SUPPORTED_KEY_HEADER_VERSION: Byte = 1
            const val MAX_SUPPORTED_KEY_HEADER_VERSION: Byte = 1
            const val MAX_PAYLOAD_SIZE = 2 * PeerInfo.MAX_PEER_INFO_SIZE
            const val PAIRING_PACKET_HEADER_SIZE: Byte = 6
            const val SPAKE2_MSG: Byte = 0
            const val PEER_INFO: Byte = 1
            fun readFrom(buffer: Buffer): PairingPacketHeader? {
                val version = buffer.readByte()
                val type = buffer.readByte()
                val payload = buffer.readInt()
                if (version < MIN_SUPPORTED_KEY_HEADER_VERSION || version > MAX_SUPPORTED_KEY_HEADER_VERSION) {
                    return null
                }
                if (type != SPAKE2_MSG && type != PEER_INFO) {
                    return null
                }
                if (payload <= 0 || payload > MAX_PAYLOAD_SIZE) {
                    return null
                }
                return PairingPacketHeader(version, type, payload)
            }
        }
    }
}
