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
package com.flyfishxu.kadb.shell

sealed class AdbShellPacket(
    open val payload: ByteArray
) {
    abstract val id: Int

    class StdOut(override val payload: ByteArray) : AdbShellPacket(payload) {
        override val id: Int = AdbShellPacketV2.ID_STDOUT
        override fun toString() = "STDOUT: ${String(payload)}"
    }

    class StdError(override val payload: ByteArray) : AdbShellPacket(payload) {
        override val id: Int = AdbShellPacketV2.ID_STDERR
        override fun toString() = "STDERR: ${String(payload)}"
    }

    class Exit(override val payload: ByteArray) : AdbShellPacket(payload) {
        override val id: Int = AdbShellPacketV2.ID_EXIT
        override fun toString() = "EXIT: ${payload[0]}"
    }
}