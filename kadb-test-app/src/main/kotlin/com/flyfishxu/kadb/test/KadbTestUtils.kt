package com.flyfishxu.kadb.test

import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object KadbTestUtils {
    
    data class DeviceInfo(
        val model: String,
        val androidVersion: String,
        val apiLevel: String,
        val brand: String,
        val manufacturer: String,
        val serialNumber: String,
        val cpuAbi: String,
        val screenSize: String,
        val batteryLevel: String
    )
    
    /**
     * Get detailed device information
     */
    suspend fun getDeviceInfo(kadb: Kadb): DeviceInfo = withContext(Dispatchers.IO) {
        val commands = mapOf(
            "model" to "getprop ro.product.model",
            "version" to "getprop ro.build.version.release",
            "api" to "getprop ro.build.version.sdk",
            "brand" to "getprop ro.product.brand",
            "manufacturer" to "getprop ro.product.manufacturer",
            "serial" to "getprop ro.serialno",
            "abi" to "getprop ro.product.cpu.abi",
            "screen" to "wm size",
            "battery" to "dumpsys battery | grep level"
        )
        
        val results = mutableMapOf<String, String>()
        
        for ((key, command) in commands) {
            try {
                val response = kadb.shell(command)
                results[key] = if (response.exitCode == 0) {
                    response.output.trim()
                } else {
                    "N/A"
                }
            } catch (e: Exception) {
                results[key] = "Error: ${e.message}"
            }
        }
        
        DeviceInfo(
            model = results["model"] ?: "Unknown",
            androidVersion = results["version"] ?: "Unknown",
            apiLevel = results["api"] ?: "Unknown",
            brand = results["brand"] ?: "Unknown",
            manufacturer = results["manufacturer"] ?: "Unknown",
            serialNumber = results["serial"] ?: "Unknown",
            cpuAbi = results["abi"] ?: "Unknown",
            screenSize = results["screen"]?.substringAfter("Physical size: ") ?: "Unknown",
            batteryLevel = results["battery"]?.substringAfter("level: ") ?: "Unknown"
        )
    }
    
    /**
     * Predefined test commands
     */
    val testCommands = listOf(
        TestCommand("Device Info", "getprop ro.product.model", "Get device model"),
        TestCommand("Android Version", "getprop ro.build.version.release", "Get Android version"),
        TestCommand("API Level", "getprop ro.build.version.sdk", "Get API level"),
        TestCommand("Screen Resolution", "wm size", "Get screen size"),
        TestCommand("Screen Density", "wm density", "Get screen density"),
        TestCommand("Battery Status", "dumpsys battery", "Get detailed battery information"),
        TestCommand("Memory Info", "cat /proc/meminfo | head -5", "Get memory usage"),
        TestCommand("CPU Info", "cat /proc/cpuinfo | grep 'model name'", "Get CPU information"),
        TestCommand("Installed Apps", "pm list packages | head -10", "List installed apps (first 10)"),
        TestCommand("Running Processes", "ps | head -10", "List running processes (first 10)"),
        TestCommand("Disk Usage", "df /data", "Get storage space information"),
        TestCommand("Network Interface", "ip addr show", "Show network interface information"),
        TestCommand("Current Time", "date", "Get device current time"),
        TestCommand("System Uptime", "uptime", "Get system uptime"),
        TestCommand("Temperature Info", "cat /sys/class/thermal/thermal_zone*/temp", "Get device temperature")
    )
    
    data class TestCommand(
        val name: String,
        val command: String,
        val description: String
    )
    
    /**
     * Check device connection status
     */
    suspend fun checkConnection(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Kadb.create(host, port).use { kadb ->
                val response = kadb.shell("echo 'ping'")
                response.exitCode == 0 && response.output.trim() == "ping"
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Discover local devices
     */
    suspend fun discoverDevices(): List<String> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<String>()
        
        // Check common emulator ports
        val commonPorts = listOf(5555, 5557, 5559, 5561)
        
        for (port in commonPorts) {
            try {
                if (checkConnection("localhost", port)) {
                    devices.add("localhost:$port")
                }
            } catch (e: Exception) {
                // Ignore connection failures
            }
        }
        
        return@withContext devices
    }
    
    /**
     * Format byte size
     */
    fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        
        return "%.2f %s".format(size, units[unitIndex])
    }
    
    /**
     * Parse memory information
     */
    fun parseMemoryInfo(meminfo: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        meminfo.lines().forEach { line ->
            when {
                line.startsWith("MemTotal:") -> {
                    val kb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0
                    result["Total Memory"] = formatBytes(kb * 1024)
                }
                line.startsWith("MemFree:") -> {
                    val kb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0
                    result["Free Memory"] = formatBytes(kb * 1024)
                }
                line.startsWith("MemAvailable:") -> {
                    val kb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0
                    result["Available Memory"] = formatBytes(kb * 1024)
                }
            }
        }
        
        return result
    }
} 