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
 */

package com.flyfishxu.kadb.transport

import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

internal interface TransportChannel : Closeable {
    suspend fun read(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int
    suspend fun write(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int
    suspend fun readExactly(dst: ByteBuffer, timeout: Long, unit: TimeUnit)
    suspend fun writeExactly(src: ByteBuffer, timeout: Long, unit: TimeUnit)
    suspend fun shutdownInput()
    suspend fun shutdownOutput()
    val localAddress: InetSocketAddress
    val remoteAddress: InetSocketAddress
    val isOpen: Boolean
}
