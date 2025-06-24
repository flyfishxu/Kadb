# Kadb Test Application

This is a desktop application for testing Kadb library functionality, built with Compose Desktop, providing a clean and intuitive graphical interface.

## Main Features

### 🔗 Connection Testing
- Test ADB connections with Android devices/emulators
- Support custom host addresses and ports
- Real-time display of connection status and test results

### 🤝 Device Pairing
- Support wireless debugging pairing for Android 11+
- Enter pairing code for device authentication
- Automatically handle SPAKE2 encryption protocol

### 💻 Shell Command Execution
- Execute arbitrary ADB Shell commands
- Display command output, error messages, and exit codes
- Support complex commands and pipe operations

### 📋 Real-time Logs
- Colored log display with different levels (INFO, SUCCESS, WARN, ERROR)
- Timestamp recording of all operations
- Selectable and copyable log content
- One-click clear history

## Usage

### Running the Application
```bash
# Execute in project root directory
./gradlew :kadb-test-app:run

# Or build executable file
./gradlew :kadb-test-app:createDistributable
```

### Connecting to Emulator
1. Start Android emulator
2. Keep default settings in the application:
   - Host address: `localhost`
   - Port: `5555`
3. Click "Test Connection" button

### Connecting to Real Device (TCP/IP)
1. Enable "Wireless debugging" on device (Android 11+)
2. Get device IP address and port
3. Enter corresponding host address and port in application
4. Click "Test Connection"

### Device Pairing Process
1. Go to "Developer options" → "Wireless debugging" on Android device
2. Click "Pair device with pairing code"
3. Record displayed IP address, port, and pairing code
4. Fill in corresponding information in application
5. Click "Start Pairing"

### Common Test Commands
```bash
# Get device information
getprop ro.build.version.release

# List installed applications
pm list packages

# Get device model
getprop ro.product.model

# View memory usage
cat /proc/meminfo

# Get CPU information
cat /proc/cpuinfo

# List running processes
ps

# View network connections
netstat

# Get screen resolution
wm size
```

## Troubleshooting

### Connection Failed
- Ensure device is on the same network
- Check firewall settings
- Verify ADB debugging is enabled
- Try restarting ADB service

### Pairing Failed
- Ensure using correct pairing code
- Check network connection
- Pairing code has time limit, regenerate if expired
- Some devices may require confirming pairing request

### Known Issues
- **Device Pairing**: Currently not available on JVM target, we are working to resolve this issue

### Command Execution Failed
- Check if command syntax is correct
- Some commands may require root permissions
- System restrictions may block certain operations

## Technical Details

- **UI Framework**: Compose Desktop
- **Async Processing**: Kotlin Coroutines
- **Network Library**: Kadb (based on Okio)
- **Encryption Support**: BouncyCastle
- **Build Tool**: Gradle with Kotlin DSL

## Log Level Description

- **INFO**: General information, such as starting operations
- **SUCCESS**: Operation completed successfully
- **WARN**: Warning information, operation completed but with exceptions
- **ERROR**: Error information, operation failed

---

💡 **Tip**: When connecting to device for the first time, you may need to confirm debug authorization on the device. 