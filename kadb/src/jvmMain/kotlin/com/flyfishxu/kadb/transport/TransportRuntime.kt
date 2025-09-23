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

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Optional AsynchronousChannelGroup injection for JVM transports.
 * Falls back to the system default group when not installed.
 * Refer to Oracle Javadoc on the default group semantics.
 */
internal object TransportRuntime {

    @Volatile
    var channelGroup: AsynchronousChannelGroup? = null
        private set

    fun install(group: AsynchronousChannelGroup) {
        channelGroup = group
    }

    /**
     * Convenience helper that installs a group backed by a fixed-size daemon thread pool.
     */
    fun installFixedThreadPool(threads: Int): AsynchronousChannelGroup {
        val normalized = threads.coerceAtLeast(2)
        val threadFactory = ThreadFactory { r ->
            Thread(r, "kadb-nio-$normalized").apply { isDaemon = true }
        }
        val executor: ExecutorService = Executors.newFixedThreadPool(normalized, threadFactory)
        val group = AsynchronousChannelGroup.withThreadPool(executor)
        install(group)
        return group
    }

    /** Clears the injected group reference without shutting it down. */
    fun clear() {
        channelGroup = null
    }
}
