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
package com.flyfishxu.kadb.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * This class provides useful functions and fields for ADB protocol details.
 */
internal object AdbProtocol {

    const val ADB_HEADER_LENGTH = 24

    const val A_STLS = 0x534c5453
    const val A_STLS_VERSION = 0x01000000

    const val AUTH_TYPE_TOKEN = 1
    const val AUTH_TYPE_SIGNATURE = 2
    const val AUTH_TYPE_RSA_PUBLIC = 3

    const val CMD_AUTH = 0x48545541
    const val CMD_CNXN = 0x4e584e43
    const val CMD_OPEN = 0x4e45504f
    const val CMD_OKAY = 0x59414b4f
    const val CMD_CLSE = 0x45534c43
    const val CMD_WRTE = 0x45545257

    const val CMD_STLS = 0x534c5453

    // Version revision in AOSP adb.h:
    // 0x01000000: original, 0x01000001: skip checksum.
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.h
    const val A_VERSION_MIN = 0x01000000
    const val A_VERSION_SKIP_CHECKSUM = 0x01000001
    const val A_VERSION = 0x01000001

    // AOSP sends A_VERSION in CNXN and clamps peer version later via min(peer, A_VERSION).
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp#327
    const val CONNECT_VERSION = A_VERSION
    // MIN maxdata required by older protocol versions.
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/master/adb.h
    const val MAX_PAYLOAD_V1 = 4 * 1024
    // Local receive cap we advertise in CNXN (1 MiB).
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/master/adb.h
    const val CONNECT_MAXDATA = 1024 * 1024

    // Advertise only features Kadb currently implements.
    // AOSP host banner format is "host::features=<csv>" (no NUL terminator).
    // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp
    // Feature names come from AOSP transport feature constants.
    // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/transport.cpp#81
    private val CONNECT_FEATURES = listOf(
        "shell_v2",
        "cmd",
        "abb_exec",
        "stat_v2",
        "ls_v2",
        "sendrecv_v2"
    )

    fun connectPayload(features: List<String> = CONNECT_FEATURES): ByteArray {
        val featureList = features.distinct().joinToString(",")
        val payload = "host::features=$featureList"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        // AOSP limits connect/auth payload to MAX_PAYLOAD_V1 before maxdata negotiation.
        // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp#338
        require(bytes.size <= MAX_PAYLOAD_V1) {
            "ADB connect banner is too long: ${bytes.size} > $MAX_PAYLOAD_V1"
        }
        return bytes
    }

    /**
     * This function performs a checksum on the ADB payload data.
     *
     * @param data   The data
     * @param offset The start offset in the data
     * @param length The number of bytes to take from the data
     * @return The checksum of the data
     */
    private fun getPayloadChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var checksum = 0
        for (i in offset until offset + length) {
            checksum += data[i].toInt() and 0xFF
        }
        return checksum
    }

    /**
     * This function generates an ADB message given the fields.
     *
     * @param command Command identifier constant
     * @param arg0    First argument
     * @param arg1    Second argument
     * @param data    The data
     * @return Byte array containing the message
     */
    private fun generateMessage(
        command: Int, arg0: Int, arg1: Int, data: ByteArray?
    ): ByteArray {
        return generateMessage(command, arg0, arg1, data, 0, data?.size ?: 0)
    }

    /**
     * This function generates an ADB message given the fields.
     *
     * @param command Command identifier constant
     * @param arg0    First argument
     * @param arg1    Second argument
     * @param data    The data
     * @param offset  The start offset in the data
     * @param length  The number of bytes to take from the data
     * @return Byte array containing the message
     */
    private fun generateMessage(
        command: Int, arg0: Int, arg1: Int, data: ByteArray?, offset: Int, length: Int
    ): ByteArray {
        // Protocol as defined at https://github.com/aosp-mirror/platform_system_core/blob/6072de17cd812daf238092695f26a552d3122f8c/adb/protocol.txt
        // struct message {
        //     unsigned command;       // command identifier constant
        //     unsigned arg0;          // first argument
        //     unsigned arg1;          // second argument
        //     unsigned data_length;   // length of payload (0 is allowed)
        //     unsigned data_check;    // checksum of data payload
        //     unsigned magic;         // command ^ 0xffffffff
        // };

        val message: ByteBuffer = if (data != null) {
            ByteBuffer.allocate(ADB_HEADER_LENGTH + length).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            ByteBuffer.allocate(ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        }

        message.putInt(command)
        message.putInt(arg0)
        message.putInt(arg1)

        if (data != null) {
            message.putInt(length)
            message.putInt(getPayloadChecksum(data, offset, length))
        } else {
            message.putInt(0)
            message.putInt(0)
        }

        message.putInt(command.inv())

        if (data != null) {
            message.put(data, offset, length)
        }

        return message.array()
    }

    /**
     * Generates an STLS message with default parameters.
     * <p>
     * STLS(version, 0, "")
     *
     * @return Byte array containing the message
     */
    fun generateStls(): ByteArray {
        return generateMessage(A_STLS, A_STLS_VERSION, 0, null)
    }
}
