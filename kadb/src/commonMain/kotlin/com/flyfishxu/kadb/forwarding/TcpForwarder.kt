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

package com.flyfishxu.kadb.forwarding

import com.flyfishxu.kadb.Kadb
import java.net.ServerSocket
import okio.buffer
import okio.sink
import okio.source

internal class TcpForwarder(
    kadb: Kadb,
    private val hostPort: Int,
    remoteDestination: String,
) : BaseForwarder(
    kadb = kadb,
    remoteDestination = remoteDestination,
    endpointDescription = "port $hostPort",
    forwardingType = "TCP",
) {

    override fun createServer(): ForwardingServer = TcpServerAdapter(hostPort)

    private class TcpServerAdapter(port: Int) : ForwardingServer {
        private val delegate = ServerSocket(port)

        override fun accept(): ForwardingClient = TcpClientAdapter(delegate.accept())

        override fun close() {
            delegate.close()
        }
    }

    private class TcpClientAdapter(
        private val delegate: java.net.Socket,
    ) : ForwardingClient {
        override val source = delegate.getInputStream().source()
        override val sink = delegate.sink().buffer()

        override fun close() {
            delegate.close()
        }
    }
}
