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

import com.flyfishxu.kadb.shell.AdbShellPacketV2
import com.flyfishxu.kadb.stream.SYNC_IDS
import okio.Buffer
import okio.BufferedSource
import java.nio.charset.StandardCharsets

internal class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payloadLength: Int,
    val checksum: Int,
    val magic: Int,
    val payload: ByteArray
) {

    override fun toString() = "${commandStr()}[${argStr(arg0)}, ${argStr(arg1)}] ${payloadStr()}"

    private fun payloadStr(): String {
        if (payloadLength == 0) return ""
        return when (command) {
            AdbProtocol.CMD_AUTH -> if (arg0 == AdbProtocol.AUTH_TYPE_RSA_PUBLIC) String(payload) else "auth[${payloadLength}]"
            AdbProtocol.CMD_WRTE -> writePayloadStr()
            AdbProtocol.CMD_OPEN -> String(payload, 0, payloadLength - 1)
            else -> "payload[$payloadLength]"
        }
    }

    private fun writePayloadStr(): String {
        shellPayloadStr()?.let { return it }
        syncPayloadStr()?.let { return it }
        return "payload[$payloadLength]"
    }

    private fun shellPayloadStr(): String? {
        val source: BufferedSource = getSource()
        if (source.buffer.size < 5) return null
        val id = source.readByte().toInt()
        if (id < 0 || id > 3) return null
        val length = source.readIntLe()
        if (length != source.buffer.size.toInt()) return null
        if (id == AdbShellPacketV2.ID_EXIT) return "[shell] exit(${source.readByte()})"
        val payload = String(payload, 5, payloadLength - 5)
        return "[shell] $payload"
    }

    private fun syncPayloadStr(): String? {
        val source: BufferedSource = getSource()
        if (source.buffer.size < 8) return null
        val id = source.readString(4, StandardCharsets.UTF_8)
        if (id !in SYNC_IDS) return null
        val arg = source.readIntLe()
        return "[sync] $id($arg)"
    }

    private fun getSource(): BufferedSource {
        return Buffer().apply { write(payload, 0, payloadLength) }
    }

    private fun argStr(arg: Int) = String.format("%X", arg)

    private fun commandStr() = when (command) {
        AdbProtocol.CMD_AUTH -> "AUTH"
        AdbProtocol.CMD_CNXN -> "CNXN"
        AdbProtocol.CMD_OPEN -> "OPEN"
        AdbProtocol.CMD_OKAY -> "OKAY"
        AdbProtocol.CMD_CLSE -> "CLSE"
        AdbProtocol.CMD_WRTE -> "WRTE"
        else -> "????"
    }
}
