package com.flyfishxu.kadb.cert.platform

import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice

actual fun defaultDeviceName(software: String?): String {
    software?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return formatDefaultDeviceName(NSProcessInfo.processInfo.processName, UIDevice.currentDevice.name)
}
