package com.flyfishxu.kadb

import com.flyfishxu.kadb.exception.AdbStreamClosed
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KadbTransportFailureTest {
    @Test
    fun rejectedServiceOpenDoesNotInvalidateTransport() {
        assertFalse(isRecoverableTransportOpenFailure(AdbStreamClosed(42)))
    }

    @Test
    fun transportEofRemainsRecoverable() {
        assertTrue(isRecoverableTransportOpenFailure(EOFException("transport eof")))
    }

    @Test
    fun wrappedTransportFailureRemainsRecoverable() {
        assertTrue(
            isRecoverableTransportOpenFailure(
                IllegalStateException("open failed", IOException("broken pipe"))
            )
        )
    }
}
