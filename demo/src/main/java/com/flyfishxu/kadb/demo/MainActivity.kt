package com.flyfishxu.kadb.demo

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        setContent {
            KadbDemoTheme {
                KadbDemoApp()
            }
        }
    }

    private fun configureSystemBars() {
        window.statusBarColor = AndroidColor.rgb(23, 32, 43)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.navigationBarColor = AndroidColor.rgb(245, 247, 250)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            window.navigationBarColor = AndroidColor.rgb(23, 32, 43)
        }
    }
}

private val AppBackground = Color(0xFFF5F7FA)
private val TopBar = Color(0xFF17202B)
private val Accent = Color(0xFF147C72)
private val AccentWarm = Color(0xFFE7A72E)
private val Danger = Color(0xFFB84A4A)
private val Panel = Color.White
private val PanelMuted = Color(0xFFF0F3F7)
private val Border = Color(0xFFE2E7EF)
private val TextPrimary = Color(0xFF17202B)
private val TextSecondary = Color(0xFF637083)

@Composable
private fun KadbDemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            secondary = AccentWarm,
            surface = Panel,
            onSurface = TextPrimary,
            background = AppBackground,
            onBackground = TextPrimary
        ),
        content = content
    )
}

@Composable
private fun KadbDemoApp() {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("5555") }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("getprop ro.product.model") }
    var client by remember { mutableStateOf<Kadb?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Disconnected") }
    var deviceSummary by remember { mutableStateOf("Connect a wireless debugging device to show device details here.") }
    val logs = remember { mutableStateListOf(LogLine.now("Kadb demo ready. Enter a device IP and port to connect.")) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            client?.close()
        }
    }

    fun addLog(message: String) {
        logs.add(0, LogLine.now(message.trimEnd()))
        if (logs.size > 8) logs.removeRange(8, logs.size)
    }

    fun parsePort(value: String, label: String): Int? {
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed !in 1..65535) {
            addLog("$label must be a number from 1 to 65535.")
            return null
        }
        return parsed
    }

    fun connect() {
        val connectPort = parsePort(port, "Connection port") ?: return
        busy = true
        status = "Connecting"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client?.close()
                    val kadb = Kadb.create(host.trim(), connectPort, connectTimeout = 8_000, socketTimeout = 30_000)
                    try {
                        val model = kadb.shell("getprop ro.product.model").allOutput.trim().ifBlank { "Unknown device" }
                        val version = kadb.shell("getprop ro.build.version.release").allOutput.trim().ifBlank { "Unknown Android" }
                        val user = kadb.shell("id").allOutput.trim().ifBlank { "shell ready" }
                        kadb to "$model / Android $version\n$user"
                    } catch (error: Throwable) {
                        kadb.close()
                        throw error
                    }
                }
            }.onSuccess { (kadb, summary) ->
                client = kadb
                deviceSummary = summary
                status = "Connected"
                addLog("Connected to $host:$connectPort\n$summary")
            }.onFailure { error ->
                client = null
                status = "Connection failed"
                addLog("Connect failed: ${error.message ?: error::class.java.simpleName}")
            }
            busy = false
        }
    }

    fun pair() {
        val pairingPort = parsePort(pairPort, "Pairing port") ?: return
        if (pairCode.isBlank()) {
            addLog("Enter the pairing code shown on the wireless debugging screen.")
            return
        }
        busy = true
        status = "Pairing"
        scope.launch {
            runCatching {
                Kadb.pair(host.trim(), pairingPort, pairCode.trim(), name = "Kadb Demo")
            }.onSuccess {
                status = if (client == null) "Paired, ready to connect" else "Connected"
                addLog("Pairing succeeded. Use the connection port from wireless debugging to connect.")
            }.onFailure { error ->
                status = "Pairing failed"
                addLog("Pairing failed: ${error.message ?: error::class.java.simpleName}")
            }
            busy = false
        }
    }

    fun runCommand(cmd: String = command.trim()) {
        val kadb = client
        if (kadb == null) {
            addLog("Connect a device first.")
            return
        }
        if (cmd.isBlank()) {
            addLog("Enter a shell command.")
            return
        }
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { kadb.shell(cmd) }
            }.onSuccess { response ->
                status = "Connected"
                addLog("$ $cmd\nexit=${response.exitCode}\n${response.allOutput.ifBlank { "<empty output>" }}")
            }.onFailure { error ->
                status = "Command failed"
                addLog("Command failed: ${error.message ?: error::class.java.simpleName}")
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
    ) {
        Header(status = status, busy = busy)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DeviceCard(summary = deviceSummary, connected = client != null)
            ConnectionPanel(
                host = host,
                onHostChange = { host = it },
                port = port,
                onPortChange = { port = it },
                pairPort = pairPort,
                onPairPortChange = { pairPort = it },
                pairCode = pairCode,
                onPairCodeChange = { pairCode = it },
                busy = busy,
                connected = client != null,
                onConnect = ::connect,
                onDisconnect = {
                    client?.close()
                    client = null
                    status = "Disconnected"
                    deviceSummary = "Connect a wireless debugging device to show device details here."
                    addLog("Disconnected.")
                },
                onPair = ::pair
            )
            ShellPanel(
                command = command,
                onCommandChange = { command = it },
                busy = busy,
                connected = client != null,
                onRunCommand = ::runCommand,
                onQuickCommand = {
                    command = it
                    runCommand(it)
                }
            )
            LogPanel(logs = logs)
        }
    }
}

@Composable
private fun Header(status: String, busy: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TopBar)
            .statusBarsPadding()
            .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Kadb Demo",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Wireless ADB connection, pairing, and shell tools",
                color = Color(0xFFD5DEE9),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        StatusPill(status = status, busy = busy)
    }
}

@Composable
private fun StatusPill(status: String, busy: Boolean) {
    val failed = status.contains("failed", ignoreCase = true)
    val connected = status == "Connected"
    val label = when {
        busy -> "Working"
        connected -> "Ready"
        failed -> "Error"
        status.startsWith("Paired") -> "Paired"
        else -> "Offline"
    }
    val container = when {
        connected -> Color(0xFFDBF3EE)
        failed -> Color(0xFFF8E4E4)
        busy -> Color(0xFFFFF3D4)
        else -> Color(0xFFE8EDF4)
    }
    val content = when {
        failed -> Danger
        connected -> Accent
        busy -> Color(0xFF805B00)
        else -> TextPrimary
    }

    Surface(
        color = container,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (failed) Danger else Color.Transparent),
        modifier = Modifier.widthIn(max = 112.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(visible = busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = Accent
                )
            }
            Text(
                text = label,
                color = content,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DeviceCard(summary: String, connected: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (connected) Color(0xFFDDF3EF) else PanelMuted),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (connected) "ADB" else "--",
                    color = if (connected) Accent else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (connected) "Device ready" else "Waiting for device",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = summary,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = if (connected) 4 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    pairPort: String,
    onPairPortChange: (String) -> Unit,
    pairCode: String,
    onPairCodeChange: (String) -> Unit,
    busy: Boolean,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPair: () -> Unit
) {
    DemoPanel(title = "Connection") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Device IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("Connection port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = pairPort,
                    onValueChange = onPairPortChange,
                    label = { Text("Pairing port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = pairCode,
                onValueChange = onPairCodeChange,
                label = { Text("Pairing code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onConnect,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Connect", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onPair,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pair", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellPanel(
    command: String,
    onCommandChange: (String) -> Unit,
    busy: Boolean,
    connected: Boolean,
    onRunCommand: () -> Unit,
    onQuickCommand: (String) -> Unit
) {
    DemoPanel(title = "Shell") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = command,
                onValueChange = onCommandChange,
                label = { Text("Command") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("getprop ro.product.model", "wm size", "pm list packages | head", "date").forEach { quick ->
                    OutlinedButton(
                        onClick = { onQuickCommand(quick) },
                        enabled = connected && !busy,
                        shape = CircleShape
                    ) {
                        Text(quick, fontSize = 12.sp)
                    }
                }
            }
            Button(
                onClick = onRunCommand,
                enabled = connected && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AccentWarm, contentColor = Color(0xFF231A08)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run command")
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<LogLine>) {
    DemoPanel(title = "Output") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            logs.forEachIndexed { index, log ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = log.time,
                        color = Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = log.message,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (index != logs.lastIndex) {
                    HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.7f))
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun DemoPanel(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

private data class LogLine(
    val time: String,
    val message: String
) {
    companion object {
        private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun now(message: String) = LogLine(
            time = formatter.format(Date()),
            message = message
        )
    }
}

@Preview
@Composable
private fun KadbDemoPreview() {
    KadbDemoTheme {
        KadbDemoApp()
    }
}
