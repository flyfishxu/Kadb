package com.flyfishxu.kadb

import android.content.Context

object KadbInitializer : ContextProvider {
    private var _applicationContext: Context? = null

    fun initialize(applicationContext: Context) {
        _applicationContext = applicationContext.applicationContext
    }

    override val ctx: Context
        get() {
            return requireNotNull(_applicationContext) { "Library has not been initialized" }
        }
}