package com.flyfishxu.kadb.test.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flyfishxu.kadb.Kadb

@Composable
fun ConnectionTab(
    host: String,
    port: String,
    pairHost: String,
    pairPort: String,
    pairCode: String,
    shellCommand: String,
    discoveredDevices: List<String>,
    isConnecting: Boolean,
    isPairing: Boolean,
    isExecuting: Boolean,
    isDiscovering: Boolean,
    kadbInstance: Kadb?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPairHostChange: (String) -> Unit,
    onPairPortChange: (String) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onShellCommandChange: (String) -> Unit,
    onConnect: () -> Unit,
    onPair: () -> Unit,
    onExecuteShell: () -> Unit,
    onDiscover: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Device discovery card
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
                        "Device Discovery",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Button(
                        onClick = onDiscover,
                        enabled = !isDiscovering
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isDiscovering) "Searching..." else "Search Devices")
                    }
                }
                
                if (discoveredDevices.isNotEmpty()) {
                    Text(
                        "Discovered devices:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    discoveredDevices.forEach { device ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelectDevice(device) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PhoneAndroid,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        device,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Connection settings card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Connection Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = { Text("Host Address") },
                        leadingIcon = { Icon(Icons.Default.Computer, null) },
                        modifier = Modifier.weight(2f)
                    )
                    
                    OutlinedTextField(
                        value = port,
                        onValueChange = onPortChange,
                        label = { Text("Port") },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Button(
                    onClick = onConnect,
                    enabled = !isConnecting && !isPairing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Cable, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isConnecting) "Connecting..." else "Test Connection")
                }
            }
        }
        
        // Device pairing card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Device Pairing",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    AssistChip(
                        onClick = { },
                        label = { Text("Android 11+", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = pairHost,
                        onValueChange = onPairHostChange,
                        label = { Text("Pairing Host") },
                        leadingIcon = { Icon(Icons.Default.Computer, null) },
                        modifier = Modifier.weight(2f)
                    )
                    
                    OutlinedTextField(
                        value = pairPort,
                        onValueChange = onPairPortChange,
                        label = { Text("Pairing Port") },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                OutlinedTextField(
                    value = pairCode,
                    onValueChange = onPairCodeChange,
                    label = { Text("Pairing Code") },
                    leadingIcon = { Icon(Icons.Default.Key, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Button(
                    onClick = onPair,
                    enabled = !isPairing && !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isPairing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Handshake, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isPairing) "Pairing..." else "Start Pairing")
                }
            }
        }
        
        // Shell command card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Shell Command Execution",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                
                OutlinedTextField(
                    value = shellCommand,
                    onValueChange = onShellCommandChange,
                    label = { Text("Shell Command") },
                    leadingIcon = { Icon(Icons.Default.Terminal, null) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    supportingText = { Text("Enter the ADB Shell command to execute") }
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onExecuteShell,
                        enabled = !isExecuting && kadbInstance != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isExecuting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isExecuting) "Executing..." else "Execute Command")
                    }
                    
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = kadbInstance != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Disconnect")
                    }
                }
            }
        }
    }
} 