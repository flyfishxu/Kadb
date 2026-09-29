package com.flyfishxu.kadb

/** TCP liveness detection, not a device wake lock. Null in [KadbOptions] keeps OS timings. */
data class TcpKeepAlive(
    val idleSeconds: Int = 1,
    val intervalSeconds: Int = 1,
    val probeCount: Int = 10,
) {
    init {
        require(idleSeconds in 1..32767)
        require(intervalSeconds in 1..32767)
        require(probeCount in 1..127)
    }
}
