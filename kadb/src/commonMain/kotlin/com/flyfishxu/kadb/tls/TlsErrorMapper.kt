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

package com.flyfishxu.kadb.tls

import com.flyfishxu.kadb.exception.AdbPairAuthException
import java.io.IOException
import java.nio.channels.InterruptedByTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

internal object TlsErrorMapper {

    fun map(throwable: Throwable): Throwable {
        val messages = buildString {
            generateSequence(throwable) { it.cause }.forEach { cause ->
                cause.message?.let { append(it.lowercase()).append('\n') }
            }
        }

        if (messages.contains("certificate_required") ||
            messages.contains("unknown_ca") ||
            messages.contains("access_denied") ||
            messages.contains("certificate_unknown")) {
            return AdbPairAuthException()
        }

        if (generateSequence(throwable) { it.cause }.any { it is InterruptedByTimeoutException }) {
            return IOException("TLS handshake timeout", throwable)
        }

        if (generateSequence(throwable) { it.cause }
                .any { it is SSLHandshakeException || it is SSLProtocolException || it is SSLException }) {
            val message = throwable.message ?: ""
            return IOException("TLS handshake failed${if (message.isNotEmpty()) ": $message" else ""}", throwable)
        }

        return throwable
    }
}
