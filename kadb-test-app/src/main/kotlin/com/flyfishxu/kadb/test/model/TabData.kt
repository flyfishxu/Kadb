package com.flyfishxu.kadb.test.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class TabData(val title: String, val icon: ImageVector) {
    Connection("Connection Test", Icons.Default.Settings),
    DeviceInfo("Device Info", Icons.Default.PhoneAndroid),
    Commands("Preset Commands", Icons.Default.Terminal),
    Logs("Operation Logs", Icons.Default.List)
} 