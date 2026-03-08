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

package com.flyfishxu.kadb.core

import com.flyfishxu.kadb.debug.log
import okio.Sink
import okio.buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AdbWriter(sink: Sink) : AutoCloseable {

    private val bufferedSink = sink.buffer()
    // Transport protocol starts at A_VERSION_MIN before CNXN completes.
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp#331
    @Volatile
    private var protocolVersion: Int = AdbProtocol.A_VERSION_MIN

    fun updateProtocolVersion(peerVersion: Int) {
        // Mirror atransport::update_version(): protocol_version = min(version, A_VERSION).
        // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/transport.cpp#1172
        protocolVersion = minOf(peerVersion, AdbProtocol.A_VERSION)
    }

    fun writeConnect(payload: ByteArray) {
        write(
            AdbProtocol.CMD_CNXN,
            AdbProtocol.CONNECT_VERSION,
            AdbProtocol.CONNECT_MAXDATA,
            payload,
            0,
            payload.size
        )
    }

    fun writeAuth(authType: Int, authPayload: ByteArray) = write(
        AdbProtocol.CMD_AUTH, authType, 0, authPayload, 0, authPayload.size
    )

    fun writeStls(version: Int) = write(
        AdbProtocol.CMD_STLS, version, 0, null, 0, 0
    )

    fun writeOpen(localId: Int, destination: String, arg1: Int = 0) {
        val destinationBytes = destination.toByteArray()
        val buffer = ByteBuffer.allocate(destinationBytes.size + 1)
        buffer.put(destinationBytes)
        buffer.put(0)
        val payload = buffer.array()
        write(AdbProtocol.CMD_OPEN, localId, arg1, payload, 0, payload.size)
    }

    fun writeWrite(localId: Int, remoteId: Int, payload: ByteArray, offset: Int, length: Int) {
        write(AdbProtocol.CMD_WRTE, localId, remoteId, payload, offset, length)
    }

    fun writeClose(localId: Int, remoteId: Int) {
        write(AdbProtocol.CMD_CLSE, localId, remoteId, null, 0, 0)
    }

    fun writeOkay(localId: Int, remoteId: Int, ackBytes: Int? = null) {
        val payload = ackBytes?.let {
            ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(it)
                .array()
        }
        write(AdbProtocol.CMD_OKAY, localId, remoteId, payload, 0, payload?.size ?: 0)
    }

    fun write(
        command: Int, arg0: Int, arg1: Int, payload: ByteArray?, offset: Int, length: Int
    ) {
        log {
            "(${Thread.currentThread().name}) > ${
                AdbMessage(
                    command, arg0, arg1, length, 0, 0, payload ?: ByteArray(0)
                )
            }"
        }
        synchronized(bufferedSink) {
            bufferedSink.apply {
                writeIntLe(command)
                writeIntLe(arg0)
                writeIntLe(arg1)
                if (payload == null) {
                    writeIntLe(0)
                    writeIntLe(0)
                } else {
                    writeIntLe(length)
                    // AOSP send_packet() writes data_check=0 starting from A_VERSION_SKIP_CHECKSUM.
                    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/transport.cpp#564
                    val dataCheck = if (protocolVersion >= AdbProtocol.A_VERSION_SKIP_CHECKSUM) {
                        0
                    } else {
                        payloadChecksum(payload, offset, length)
                    }
                    writeIntLe(dataCheck)
                }
                writeIntLe(command xor -0x1)
                if (payload != null) {
                    write(payload, offset, length)
                }
                flush()
            }
        }
    }

    private fun payloadChecksum(payload: ByteArray, offset: Int, length: Int): Int {
        // ADB checksum is the sum of payload bytes only.
        // https://android.googlesource.com/platform/system/core/+/android-4.4_r1.1/adb/transport.cpp#L191
        require(offset >= 0 && length >= 0 && offset + length <= payload.size) {
            "payloadChecksum out of bounds: offset=$offset length=$length size=${payload.size}"
        }
        var checksum = 0
        for (i in offset until offset + length) {
            checksum += payload[i].toUByte().toInt()
        }
        return checksum
    }

    override fun close() {
        bufferedSink.close()
    }
}
