package com.flyfishxu.kadb

import android.os.Build


// TODO: DO NOT HARD CODE THE DEVICE NAME
actual fun AdbKeyPair.Companion.getDeviceName(): String {
    return "${Build.MODEL.replace(" ", "")}@Kadb"
}