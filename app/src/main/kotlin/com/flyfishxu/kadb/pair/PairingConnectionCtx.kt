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

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import com.flyfishxu.kadb.AdbKeyPair
import com.flyfishxu.kadb.AndroidPubkey.encodeWithName
import com.flyfishxu.kadb.SslUtils
import com.flyfishxu.kadb.SslUtils.getSslContext
import com.flyfishxu.kadb.pair.PairingAuthCtx.Companion.createAlice
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Arrays
import java.util.Objects
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class PairingConnectionCtx(
    host: String, private val mPort: Int, pswd: ByteArray, keyPair: AdbKeyPair,
    deviceName: String
) : Closeable {
    private val mHost: String
    private val mPswd: ByteArray
    private val mPeerInfo: PeerInfo
    private val mSslContext: SSLContext
    private val mRole = Role.Client
    private var mInputStream: DataInputStream? = null
    private var mOutputStream: DataOutputStream? = null
    private var mPairingAuthCtx: PairingAuthCtx? = null
    private var mState = State.Ready

    init {
        mHost = Objects.requireNonNull(host)
        mPswd = Objects.requireNonNull(pswd)
        mPeerInfo = PeerInfo(
            PeerInfo.ADB_RSA_PUB_KEY, encodeWithName(
                (keyPair.publicKey as RSAPublicKey), Objects.requireNonNull(deviceName)
            )
        )
        mSslContext = getSslContext(keyPair)
    }

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
        val socket: Socket
        socket = if (mRole == Role.Server) {
            val sslServerSocket =
                mSslContext.serverSocketFactory.createServerSocket(mPort) as SSLServerSocket
            sslServerSocket.accept()
        } else { // role == Role.Client
            Socket(mHost, mPort)
        }
        socket.tcpNoDelay = true

        // We use custom SSLContext to allow any SSL certificates
        val sslSocket =
            mSslContext.socketFactory.createSocket(socket, mHost, mPort, true) as SSLSocket
        sslSocket.startHandshake()
        Log.d(TAG, "Handshake succeeded.")
        mInputStream = DataInputStream(sslSocket.inputStream)
        mOutputStream = DataOutputStream(sslSocket.outputStream)

        // To ensure the connection is not stolen while we do the PAKE, append the exported key material from the
        // tls connection to the password.
        val keyMaterial = exportKeyingMaterial(sslSocket, EXPORT_KEY_SIZE)
        val passwordBytes = ByteArray(mPswd.size + keyMaterial.size)
        System.arraycopy(mPswd, 0, passwordBytes, 0, mPswd.size)
        System.arraycopy(keyMaterial, 0, passwordBytes, mPswd.size, keyMaterial.size)
        val pairingAuthCtx = createAlice(passwordBytes)
            ?: throw IOException("Unable to create PairingAuthCtx.")
        mPairingAuthCtx = pairingAuthCtx
    }

    @SuppressLint("PrivateApi") // Conscrypt is a stable private API
    @Throws(SSLException::class)
    private fun exportKeyingMaterial(sslSocket: SSLSocket, length: Int): ByteArray {
        // Conscrypt#exportKeyingMaterial(SSLSocket socket, String label, byte[] context, int length): byte[]
        //          throws SSLException
        return try {
            val conscryptClass: Class<*>
            conscryptClass = if (SslUtils.customConscrypt) {
                Class.forName("org.conscrypt.Conscrypt")
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Although support for conscrypt has been added in Android 5.0 (Lollipop),
                // TLS1.3 isn't supported until Android 9 (Pie).
                throw SSLException("TLSv1.3 isn't supported on your platform. Use custom Conscrypt library instead.")
            } else {
                Class.forName("com.android.org.conscrypt.Conscrypt")
            }
            val exportKeyingMaterial = conscryptClass.getMethod(
                "exportKeyingMaterial", SSLSocket::class.java,
                String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType
            )
            exportKeyingMaterial.invoke(
                null,
                sslSocket,
                EXPORTED_KEY_LABEL,
                null,
                length
            ) as ByteArray
        } catch (e: SSLException) {
            throw e
        } catch (th: Throwable) {
            throw SSLException(th)
        }
    }

    @Throws(IOException::class)
    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(PairingPacketHeader.PAIRING_PACKET_HEADER_SIZE.toInt())
            .order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)
        mOutputStream!!.write(buffer.array())
        mOutputStream!!.write(payload)
    }

    @Throws(IOException::class)
    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(PairingPacketHeader.PAIRING_PACKET_HEADER_SIZE.toInt())
        mInputStream!!.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun createHeader(type: Byte, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(
            PairingPacketHeader.CURRENT_KEY_HEADER_VERSION,
            type,
            payloadSize
        )
    }

    private fun checkHeaderType(expected: Byte, actual: Byte): Boolean {
        if (expected != actual) {
            Log.e(TAG, "Unexpected header type (expected=$expected actual=$actual)")
            return false
        }
        return true
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
                PairingPacketHeader.SPAKE2_MSG,
                theirHeader.type
            )
        ) return false

        // Read the SPAKE2 msg payload and initialize the cipher for encrypting the PeerInfo and certificate.
        val theirMsg = ByteArray(theirHeader.payloadSize)
        mInputStream!!.readFully(theirMsg)
        return try {
            mPairingAuthCtx!!.initCipher(theirMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize pairing cipher")
            throw (IOException().initCause(e) as IOException)
        }
    }

    @Throws(IOException::class)
    private fun doExchangePeerInfo(): Boolean {
        // Encrypt PeerInfo
        val buffer = ByteBuffer.allocate(PeerInfo.MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        mPeerInfo.writeTo(buffer)
        val outBuffer = mPairingAuthCtx!!.encrypt(buffer.array())
        if (outBuffer == null) {
            Log.e(TAG, "Failed to encrypt peer info")
            return false
        }

        // Write out the packet header
        val ourHeader = createHeader(PairingPacketHeader.PEER_INFO, outBuffer.size)
        // Write out the encrypted payload
        writeHeader(ourHeader, outBuffer)

        // Read in the peer's packet header
        val theirHeader = readHeader()
        if (theirHeader == null || !checkHeaderType(
                PairingPacketHeader.PEER_INFO,
                theirHeader.type
            )
        ) return false

        // Read in the encrypted peer certificate
        val theirMsg = ByteArray(theirHeader.payloadSize)
        mInputStream!!.readFully(theirMsg)

        // Try to decrypt the certificate
        val decryptedMsg = mPairingAuthCtx!!.decrypt(theirMsg)
        if (decryptedMsg == null) {
            Log.e(TAG, "Unsupported payload while decrypting peer info.")
            return false
        }

        // The decrypted message should contain the PeerInfo.
        if (decryptedMsg.size != PeerInfo.MAX_PEER_INFO_SIZE) {
            Log.e(
                TAG,
                "Got size=" + decryptedMsg.size + " PeerInfo.size=" + PeerInfo.MAX_PEER_INFO_SIZE
            )
            return false
        }
        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decryptedMsg))
        Log.d(TAG, theirPeerInfo.toString())
        return true
    }

    override fun close() {
        Arrays.fill(mPswd, 0.toByte())
        try {
            mInputStream!!.close()
        } catch (ignore: IOException) {
        }
        try {
            mOutputStream!!.close()
        } catch (ignore: IOException) {
        }
        if (mState != State.Ready) {
            mPairingAuthCtx!!.destroy()
        }
    }

    private enum class State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    internal enum class Role {
        Client,
        Server
    }

    private class PeerInfo(private val type: Byte, data: ByteArray) {
        private val data = ByteArray(MAX_PEER_INFO_SIZE - 1)

        init {
            System.arraycopy(data, 0, this.data, 0, Math.min(data.size, MAX_PEER_INFO_SIZE - 1))
        }

        fun writeTo(buffer: ByteBuffer) {
            buffer.put(type).put(data)
        }

        override fun toString(): String {
            return "PeerInfo{" +
                    "type=" + type +
                    ", data=" + Arrays.toString(data) +
                    '}'
        }

        companion object {
            const val MAX_PEER_INFO_SIZE = 1 shl 13
            const val ADB_RSA_PUB_KEY: Byte = 0
            const val ADB_DEVICE_GUID: Byte = 0
            fun readFrom(buffer: ByteBuffer): PeerInfo {
                val type = buffer.get()
                val data = ByteArray(MAX_PEER_INFO_SIZE - 1)
                buffer[data]
                return PeerInfo(type, data)
            }
        }
    }

    private class PairingPacketHeader(
        private val version: Byte,
        val type: Byte,
        val payloadSize: Int
    ) {
        fun writeTo(buffer: ByteBuffer) {
            buffer.put(version).put(type).putInt(payloadSize)
        }

        override fun toString(): String {
            return "PairingPacketHeader{" +
                    "version=" + version +
                    ", type=" + type +
                    ", payloadSize=" + payloadSize +
                    '}'
        }

        companion object {
            const val CURRENT_KEY_HEADER_VERSION: Byte = 1
            const val MIN_SUPPORTED_KEY_HEADER_VERSION: Byte = 1
            const val MAX_SUPPORTED_KEY_HEADER_VERSION: Byte = 1
            const val MAX_PAYLOAD_SIZE = 2 * PeerInfo.MAX_PEER_INFO_SIZE
            const val PAIRING_PACKET_HEADER_SIZE: Byte = 6
            const val SPAKE2_MSG: Byte = 0
            const val PEER_INFO: Byte = 1
            fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
                val version = buffer.get()
                val type = buffer.get()
                val payload = buffer.getInt()
                if (version < MIN_SUPPORTED_KEY_HEADER_VERSION || version > MAX_SUPPORTED_KEY_HEADER_VERSION) {
                    Log.e(
                        TAG,
                        "PairingPacketHeader version mismatch (us=" + CURRENT_KEY_HEADER_VERSION
                                + " them=" + version + ")"
                    )
                    return null
                }
                if (type != SPAKE2_MSG && type != PEER_INFO) {
                    Log.e(TAG, "Unknown PairingPacket type $type")
                    return null
                }
                if (payload <= 0 || payload > MAX_PAYLOAD_SIZE) {
                    Log.e(TAG, "Header payload not within a safe payload size (size=$payload)")
                    return null
                }
                return PairingPacketHeader(version, type, payload)
            }
        }
    }

    companion object {
        val TAG = PairingConnectionCtx::class.java.simpleName
        const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        const val EXPORT_KEY_SIZE = 64
    }
}
