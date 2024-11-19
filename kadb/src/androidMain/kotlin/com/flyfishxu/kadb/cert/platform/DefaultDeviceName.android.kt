package com.flyfishxu.kadb.cert.platform

import android.os.Build

actual fun defaultDeviceName(software: String): String {
    return "${Build.MODEL.replace(" ", "")}@$software"
}