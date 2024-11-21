package com.flyfishxu.kadb.pair

internal actual fun PairingConnectionCtx.getConscryptClass(): Class<*> {
    return try {
        Class.forName("org.conscrypt.Conscrypt")
    } catch (_: ClassNotFoundException) {
        throw IllegalStateException("conscrypt-openjdk not found, add it to your dependencies")
    }
}