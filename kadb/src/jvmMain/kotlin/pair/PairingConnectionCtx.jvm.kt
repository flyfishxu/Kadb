package com.flyfishxu.kadb.pair

actual fun PairingConnectionCtx.getConscryptClass(): Class<*> {
    return Class.forName("org.conscrypt.Conscrypt")
}