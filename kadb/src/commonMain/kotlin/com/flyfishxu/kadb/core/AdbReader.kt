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
import okio.Source
import okio.buffer
import java.io.IOException

internal class AdbReader(
    source: Source,
    maxPayloadSize: Int = AdbProtocol.CONNECT_MAXDATA
) : AutoCloseable {

    private val bufferedSource = source.buffer()
    // Keep negotiated limits non-negative; AOSP negotiates max_payload with min(payload, MAX_PAYLOAD).
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/transport.cpp#1172
    private val localHardCap = maxPayloadSize
        .coerceAtLeast(0)
        .coerceAtMost(AdbProtocol.CONNECT_MAXDATA)
    @Volatile
    private var inboundMaxPayloadSize = localHardCap
    // CNXN maxdata is the peer's receive limit (what we send).
    // https://android.googlesource.com/platform/system/core/+/dd7bc3319deb2b77c5d07a51b7d6cd7e11b5beb0/adb/protocol.txt
    // AOSP negotiates max payload as min(peer_max, MAX_PAYLOAD).
    // https://android.googlesource.com/platform/system/core/+/3d2904c%5E%21/
    // Inbound payload length is validated against the negotiated cap.
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-vts-16.0_r1/apacket_reader.cpp
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/master/adb.h

    fun setInboundMaxPayloadSize(maxPayloadSize: Int) {
        inboundMaxPayloadSize = maxPayloadSize
            .coerceAtLeast(0)
            .coerceAtMost(AdbProtocol.CONNECT_MAXDATA)
    }

    fun readMessage(): AdbMessage {
        synchronized(bufferedSource) {
            bufferedSource.apply {
                val command = readIntLe()
                val arg0 = readIntLe()
                val arg1 = readIntLe()
                val payloadLength = readIntLe()
                // Validate data_length against the negotiated cap (min(peerMaxdata, MAX_PAYLOAD)).
                // https://android.googlesource.com/platform/system/core/+/3d2904c%5E%21/
                // https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-vts-16.0_r1/apacket_reader.cpp
                // https://android.googlesource.com/platform/system/core/+/dd7bc3319deb2b77c5d07a51b7d6cd7e11b5beb0/adb/protocol.txt
                if (payloadLength < 0 || payloadLength > inboundMaxPayloadSize) {
                    throw IOException("Invalid ADB payload length: $payloadLength (max=$inboundMaxPayloadSize)")
                }
                val checksum = readIntLe()
                val magic = readIntLe()
                // AOSP check_header() rejects packets when magic != (command ^ 0xffffffff).
                // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/transport.cpp#1692
                val expectedMagic = command.inv()
                if (magic != expectedMagic) {
                    throw IOException("Invalid ADB magic: expected=$expectedMagic actual=$magic command=$command")
                }
                val payload = readByteArray(payloadLength.toLong())
                return AdbMessage(
                    command,
                    arg0,
                    arg1,
                    payloadLength,
                    checksum,
                    magic,
                    payload
                ).also {
                    log { "(${Thread.currentThread().name}) < $it" }
                }
            }
        }
    }

    override fun close() {
        bufferedSource.close()
    }
}
