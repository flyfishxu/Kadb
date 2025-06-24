package com.flyfishxu.kadb.test

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kadb Test Application",
        state = rememberWindowState(width = 1200.dp, height = 800.dp)
    ) {
        KadbTestApp()
    }
}

 