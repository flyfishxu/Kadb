package com.flyfishxu.kadb.forwarding

import com.flyfishxu.kadb.Kadb
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TcpForwarderTest {
    @Test
    fun listenerClosesDuringStartupAndCanRestart() {
        Kadb("127.0.0.1", 5555).use { kadb ->
            val forwarder = TcpForwarder(kadb, hostPort = 0, targetPort = 8080)
            repeat(3) {
                forwarder.start()
                assertFailsWith<IllegalStateException> { forwarder.start() }
                // No client needs to connect before close can stop the accept loop.
                forwarder.close()
                forwarder.close()
            }
        }
    }
}
