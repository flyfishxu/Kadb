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

    const val CONNECT_VERSION = 0x01000000
    const val CONNECT_MAXDATA = 1024 * 1024

    val CONNECT_PAYLOAD = "host::\u0000".toByteArray()

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