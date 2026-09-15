package com.flyfishxu.kadb.transport

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.flyfishxu.kadb.TcpKeepAlive
import java.net.Socket

internal fun configureTcpKeepAlive(socket: Socket, policy: TcpKeepAlive) {
    // Before Q, fromSocket borrows the socket FD instead of duplicating it. Do not
    // risk closing that descriptor; those devices retain basic SO_KEEPALIVE.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    try {
        ParcelFileDescriptor.fromSocket(socket)?.use { descriptor ->
            // Linux UAPI <linux/tcp.h>. These options aren't public OsConstants.
            Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.IPPROTO_TCP, 4, policy.idleSeconds)
            Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.IPPROTO_TCP, 5, policy.intervalSeconds)
            Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.IPPROTO_TCP, 6, policy.probeCount)
        }
    } catch (error: Exception) {
        Log.w("Kadb", "TCP keepalive timings unavailable; retaining platform timings", error)
    }
}
