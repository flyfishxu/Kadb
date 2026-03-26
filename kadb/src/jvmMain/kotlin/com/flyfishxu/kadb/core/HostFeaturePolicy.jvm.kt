package com.flyfishxu.kadb.core

internal actual fun aospDefaultDelayedAckEnabled(): Boolean {
    return JvmHostFeatureDefaults.burstModeOverride ?: (System.getenv("ADB_BURST_MODE") == "1")
}

internal object JvmHostFeatureDefaults {
    @Volatile
    var burstModeOverride: Boolean? = null
}
