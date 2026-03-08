package com.flyfishxu.kadb.cert.platform

actual fun defaultDeviceName(software: String?): String {
    software
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    val userName = System.getProperty("user.name")
    val hostName = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
    return formatDefaultDeviceName(userName, hostName)
}
