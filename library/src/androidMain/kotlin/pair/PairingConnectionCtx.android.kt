package com.flyfishxu.kadb.pair

import android.annotation.SuppressLint
import android.os.Build
import com.flyfishxu.kadb.SslUtils
import javax.net.ssl.SSLException

@SuppressLint("PrivateApi")
actual fun PairingConnectionCtx.getConscryptClass(): Class<*> {
    return if (SslUtils.customConscrypt) {
        Class.forName("org.conscrypt.Conscrypt")
    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        // Although support for conscrypt has been added in Android 5.0 (Lollipop),
        // TLS1.3 isn't supported until Android 9 (Pie).
        throw SSLException("TLSv1.3 isn't supported on your platform. Use custom Conscrypt library instead.")
    } else {
        Class.forName("com.android.org.conscrypt.Conscrypt")
    }
}