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

package com.flyfishxu.kadb.queue

import com.flyfishxu.kadb.core.AdbMessage
import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader

internal class AdbMessageQueue(private val adbReader: AdbReader) : AutoCloseable,
    MessageQueue<AdbMessage>() {

    override fun readMessage() = adbReader.readMessage()

    override fun getLocalId(message: AdbMessage) = message.arg1

    override fun getCommand(message: AdbMessage) = message.command

    override fun close() = adbReader.close()

    override fun isCloseCommand(message: AdbMessage) = message.command == AdbProtocol.CMD_CLSE
}
