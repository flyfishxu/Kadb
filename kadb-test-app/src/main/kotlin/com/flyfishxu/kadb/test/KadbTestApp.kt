package com.flyfishxu.kadb.test

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.test.model.LogEntry
import com.flyfishxu.kadb.test.model.TabData
import com.flyfishxu.kadb.test.ui.components.ConnectionStatusIndicator
import com.flyfishxu.kadb.test.ui.tabs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KadbTestApp() {
    var host by remember { mutableStateOf("localhost") }
    var port by remember { mutableStateOf("5555") }
    var pairHost by remember { mutableStateOf("192.168.1.100") }
    var pairPort by remember { mutableStateOf("37755") }
    var pairCode by remember { mutableStateOf("123456") }
    var shellCommand by remember { mutableStateOf("echo 'Hello ADB'") }
    var isConnecting by remember { mutableStateOf(false) }
    var isPairing by remember { mutableStateOf(false) }
    var isExecuting by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isGettingDeviceInfo by remember { mutableStateOf(false) }
    var kadbInstance by remember { mutableStateOf<Kadb?>(null) }
    var logs by remember { mutableStateOf(listOf<LogEntry>()) }
    var discoveredDevices by remember { mutableStateOf(listOf<String>()) }
    var deviceInfo by remember { mutableStateOf<KadbTestUtils.DeviceInfo?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    
    val scope = rememberCoroutineScope()
    val logsListState = rememberLazyListState()
    
    // Auto scroll to latest log
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logsListState.animateScrollToItem(logs.size - 1)
        }
    }
    
    fun addLog(level: String, message: String, isError: Boolean = false) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())
        logs = logs + LogEntry(timestamp, level, message, isError)
    }
    
    fun clearLogs() {
        logs = emptyList()
    }
    
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top app bar
                TopAppBar(
                    title = { 
                        Text(
                            "Kadb Test Tool",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        ) 
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    actions = {
                        // Connection status indicator
                        ConnectionStatusIndicator(
                            isConnected = kadbInstance != null,
                            connectionInfo = if (kadbInstance != null) "$host:$port" else null
                        )
                    }
                )
                
                // Tab row
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TabData.entries.forEachIndexed { index, tabData ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { 
                                Text(
                                    tabData.title,
                                    style = MaterialTheme.typography.labelLarge
                                ) 
                            },
                            icon = { 
                                Icon(
                                    tabData.icon, 
                                    contentDescription = tabData.title,
                                    modifier = Modifier.size(20.dp)
                                ) 
                            }
                        )
                    }
                }
                
                // Tab content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    when (selectedTab) {
                        0 -> ConnectionTab(
                            host = host,
                            port = port,
                            pairHost = pairHost,
                            pairPort = pairPort,
                            pairCode = pairCode,
                            shellCommand = shellCommand,
                            discoveredDevices = discoveredDevices,
                            isConnecting = isConnecting,
                            isPairing = isPairing,
                            isExecuting = isExecuting,
                            isDiscovering = isDiscovering,
                            kadbInstance = kadbInstance,
                            onHostChange = { host = it },
                            onPortChange = { port = it },
                            onPairHostChange = { pairHost = it },
                            onPairPortChange = { pairPort = it },
                            onPairCodeChange = { pairCode = it },
                            onShellCommandChange = { shellCommand = it },
                            onConnect = {
                                scope.launch {
                                    isConnecting = true
                                    addLog("INFO", "Attempting to connect to $host:$port")
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val kadb = Kadb.create(host, port.toInt())
                                            val response = kadb.shell("echo 'connection test'")
                                            if (response.exitCode == 0) {
                                                kadbInstance = kadb
                                                addLog("SUCCESS", "Connection successful! Device response: ${response.output.trim()}")
                                            } else {
                                                addLog("ERROR", "Connection failed, exit code: ${response.exitCode}", true)
                                                kadb.close()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        addLog("ERROR", "Connection failed: ${e.message}", true)
                                    } finally {
                                        isConnecting = false
                                    }
                                }
                            },
                            onPair = {
                                scope.launch {
                                    isPairing = true
                                    addLog("INFO", "Attempting to pair with $pairHost:$pairPort, pairing code: $pairCode")
                                    try {
                                        withContext(Dispatchers.IO) {
                                            Kadb.pair(pairHost, pairPort.toInt(), pairCode)
                                            addLog("SUCCESS", "Pairing successful!")
                                        }
                                    } catch (e: Exception) {
                                        addLog("ERROR", "Pairing failed: ${e.message}", true)
                                    } finally {
                                        isPairing = false
                                    }
                                }
                            },
                            onExecuteShell = {
                                scope.launch {
                                    isExecuting = true
                                    addLog("INFO", "Executing command: $shellCommand")
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val kadb = kadbInstance ?: Kadb.create(host, port.toInt())
                                            val response = kadb.shell(shellCommand)
                                            addLog("INFO", "Exit code: ${response.exitCode}")
                                            if (response.output.isNotEmpty()) {
                                                addLog("SUCCESS", "Output: ${response.output}")
                                            }
                                            if (response.errorOutput.isNotEmpty()) {
                                                addLog("WARN", "Error output: ${response.errorOutput}")
                                            }
                                            if (kadbInstance == null) kadb.close()
                                        }
                                    } catch (e: Exception) {
                                        addLog("ERROR", "Command execution failed: ${e.message}", true)
                                    } finally {
                                        isExecuting = false
                                    }
                                }
                            },
                            onDiscover = {
                                scope.launch {
                                    isDiscovering = true
                                    addLog("INFO", "Searching for local devices...")
                                    try {
                                        discoveredDevices = KadbTestUtils.discoverDevices()
                                        if (discoveredDevices.isNotEmpty()) {
                                            addLog("SUCCESS", "Found ${discoveredDevices.size} device(s): ${discoveredDevices.joinToString()}")
                                        } else {
                                            addLog("WARN", "No devices found")
                                        }
                                    } catch (e: Exception) {
                                        addLog("ERROR", "Device discovery failed: ${e.message}", true)
                                    } finally {
                                        isDiscovering = false
                                    }
                                }
                            },
                            onSelectDevice = { device ->
                                val parts = device.split(":")
                                if (parts.size == 2) {
                                    host = parts[0]
                                    port = parts[1]
                                }
                            },
                            onDisconnect = {
                                kadbInstance?.close()
                                kadbInstance = null
                                deviceInfo = null
                                addLog("INFO", "Disconnected")
                            }
                        )
                        
                        1 -> DeviceInfoTab(
                            deviceInfo = deviceInfo,
                            isGettingDeviceInfo = isGettingDeviceInfo,
                            kadbInstance = kadbInstance,
                            onRefresh = {
                                scope.launch {
                                    val kadb = kadbInstance
                                    if (kadb != null) {
                                        isGettingDeviceInfo = true
                                        addLog("INFO", "Getting device information...")
                                        try {
                                            deviceInfo = KadbTestUtils.getDeviceInfo(kadb)
                                            addLog("SUCCESS", "Device information retrieved successfully")
                                        } catch (e: Exception) {
                                            addLog("ERROR", "Failed to get device information: ${e.message}", true)
                                        } finally {
                                            isGettingDeviceInfo = false
                                        }
                                    } else {
                                        addLog("ERROR", "Please connect to a device first", true)
                                    }
                                }
                            }
                        )
                        
                        2 -> PresetCommandsTab(
                            kadbInstance = kadbInstance,
                            onCommandSelect = { command ->
                                shellCommand = command
                                selectedTab = 0
                            },
                            onCommandExecute = { testCommand ->
                                scope.launch {
                                    val kadb = kadbInstance
                                    if (kadb != null) {
                                        addLog("INFO", "Executing preset command: ${testCommand.name}")
                                        try {
                                            withContext(Dispatchers.IO) {
                                                val response = kadb.shell(testCommand.command)
                                                addLog("INFO", "Exit code: ${response.exitCode}")
                                                if (response.output.isNotEmpty()) {
                                                    addLog("SUCCESS", "${testCommand.name} result:\n${response.output}")
                                                }
                                                if (response.errorOutput.isNotEmpty()) {
                                                    addLog("WARN", "Error output: ${response.errorOutput}")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            addLog("ERROR", "Command execution failed: ${e.message}", true)
                                        }
                                    } else {
                                        addLog("ERROR", "Please connect to a device first", true)
                                    }
                                }
                            }
                        )
                        
                        3 -> LogsTab(
                            logs = logs,
                            listState = logsListState,
                            onClearLogs = { clearLogs() }
                        )
                    }
                }
            }
        }
    }
} 