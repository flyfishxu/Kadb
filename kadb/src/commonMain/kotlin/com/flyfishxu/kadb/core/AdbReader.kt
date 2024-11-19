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

internal class AdbReader(source: Source) : AutoCloseable {

    private val bufferedSource = source.buffer()

    fun readMessage(): AdbMessage {
        synchronized(bufferedSource) {
            bufferedSource.apply {
                val command = readIntLe()
                val arg0 = readIntLe()
                val arg1 = readIntLe()
                val payloadLength = readIntLe()
                val checksum = readIntLe()
                val magic = readIntLe()
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
