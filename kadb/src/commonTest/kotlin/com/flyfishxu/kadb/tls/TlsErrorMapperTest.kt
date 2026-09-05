package com.flyfishxu.kadb.tls

import com.flyfishxu.kadb.exception.AdbPairAuthException
import java.io.IOException
import javax.net.ssl.SSLProtocolException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TlsErrorMapperTest {

    @Test
    fun protocolErrorIsMappedToPairAuthException() {
        // adbd throws SSLProtocolException with a "protocol error" message when
        // the device is unauthorized or not paired.
        val mapped = TlsErrorMapper.map(SSLProtocolException("Protocol error"))

        assertIs<AdbPairAuthException>(mapped)
    }

    @Test
    fun protocolErrorInCauseChainIsMappedToPairAuthException() {
        val mapped = TlsErrorMapper.map(
            IOException("handshake aborted", SSLProtocolException("PROTOCOL ERROR"))
        )

        assertIs<AdbPairAuthException>(mapped)
    }

    @Test
    fun unrelatedProtocolExceptionFallsBackToHandshakeFailure() {
        // An SSLProtocolException without a pairing-related message should not be
        // treated as an auth failure.
        val mapped = TlsErrorMapper.map(SSLProtocolException("record overflow"))

        assertTrue(mapped !is AdbPairAuthException)
        assertIs<IOException>(mapped)
        assertTrue(mapped.message?.contains("TLS handshake failed") == true)
    }
}
