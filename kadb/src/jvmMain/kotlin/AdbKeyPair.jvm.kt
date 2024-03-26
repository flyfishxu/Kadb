package com.flyfishxu.kadb

actual fun AdbKeyPair.Companion.getDeviceName(): String {
    val userName = System.getProperty("user.name")
    val hostName = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "unknown"

    return "$userName@$hostName@Kadb"
}
