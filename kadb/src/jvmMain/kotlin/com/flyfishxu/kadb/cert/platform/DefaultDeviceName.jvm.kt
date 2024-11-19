package com.flyfishxu.kadb.cert.platform

actual fun defaultDeviceName(software: String): String {
    val userName = System.getProperty("user.name")
    val hostName = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "unknown"
    return "$userName@$hostName@$software"
}