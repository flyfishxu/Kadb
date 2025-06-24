package com.flyfishxu.kadb.test.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.test.KadbTestUtils
import com.flyfishxu.kadb.test.ui.components.InfoCard

@Composable
fun DeviceInfoTab(
    deviceInfo: KadbTestUtils.DeviceInfo?,
    isGettingDeviceInfo: Boolean,
    kadbInstance: Kadb?,
    onRefresh: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Device Information",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                
                Button(
                    onClick = onRefresh,
                    enabled = !isGettingDeviceInfo && kadbInstance != null
                ) {
                    if (isGettingDeviceInfo) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isGettingDeviceInfo) "Loading..." else "Refresh")
                }
            }
            
            if (deviceInfo != null) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        InfoCard("Device Model", deviceInfo.model, Icons.Default.PhoneAndroid)
                    }
                    item {
                        InfoCard("Brand", deviceInfo.brand, Icons.Default.Business)
                    }
                    item {
                        InfoCard("Manufacturer", deviceInfo.manufacturer, Icons.Default.Factory)
                    }
                    item {
                        InfoCard("Android Version", deviceInfo.androidVersion, Icons.Default.Android)
                    }
                    item {
                        InfoCard("API Level", deviceInfo.apiLevel, Icons.Default.Api)
                    }
                    item {
                        InfoCard("CPU Architecture", deviceInfo.cpuAbi, Icons.Default.Memory)
                    }
                    item {
                        InfoCard("Screen Size", deviceInfo.screenSize, Icons.Default.ScreenRotation)
                    }
                    item {
                        InfoCard("Serial Number", deviceInfo.serialNumber, Icons.Default.Numbers)
                    }
                    item {
                        InfoCard("Battery Level", deviceInfo.batteryLevel, Icons.Default.BatteryFull)
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "Please connect to a device first",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Click the refresh button after connecting to get device information",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
} 