# Kadb
A Kotlin based Android library to connect Android devices directly without ADB binary file.

## Express Gratitude 

- [Dadb](https://github.com/mobile-dev-inc/dadb): Kadb is based on the second development of Dadb, and we would like to thank mobile-dev-inc for all their previous efforts!

## Features and Differences

- Connect to an Android device and execute ADB commands without ADB binary file.
- Support ADB SSL connection (Requires target device running Android 11 and above).
- Support ADB Pair instead of the legacy way to authenticate with the target Android device you want to connect (Requires target device running Android 11 and above).
- Support for Android DocumentFile in push and pull methods.
- Throw an exception if the target device is not authorized (Dadb can't check if the connected device is authorized)
- Lower Android version support compared to Dadb.

## Usages

### Connect to a device

Connect to `emulator-5554` and install `apkFile`:

```kotlin
Dadb.create("localhost", 5555).use { dadb ->
    dadb.install(apkFile)
}
```

*Note: Connect to the odd adb daemon port (5555), not the even emulator console port (5554)*

### Pair with new device

Host: 10.0.0.175;  Port: 37755;  PairCode: 643102

```kotlin
Dadb.pair("10.0.0.175", 37755, "643102")
```

*Note: Pair only works when target device running Android 11 and above*

### Discover a Device

The following discovers and returns a connected device or emulator. If there are multiple it returns the first one found.

```kotlin
val dadb = Dadb.discover()
if (dadb == null) throw RuntimeException("No adb device found")
```

Use the following API if you want to list all available devices:

```kotlin
val dadbs = Dadb.list()
```

### Connecting to a physical device

*Prerequisite: Connecting to a physical device requires a running adb server. In most cases, this means that you must have the `adb` binary installed on your machine.*

The `Dadb.discover()` and `Dadb.list()` methods now both support USB-connected devices.

```kotlin
// Both of these will include any USB-connected devices if they are available
val dadb = Dadb.discover()
val dadbs = Dadb.list()
```

If you'd like to connect directly to a physical device via its serial number. Use the following API:

```kotlin
val dadb = AdbServer.createDadb(
    adbServerHost = "localhost",
    adbServerPort = 5037,
    deviceQuery = "host:transport:${serialNumber}"
)
```

### Install / Uninstall APK

```kotlin
dadb.install(exampleApkFile)
dadb.uninstall("com.example.app")
```

### Push / Pull Files

```kotlin
dadb.push(srcFile, "/data/local/tmp/dst.txt")
dadb.pull(dstFile, "/data/local/tmp/src.txt")
```

### Execute Shell Command

```kotlin
val response = dadb.shell("echo hello")
assert(response.exitCode == 0)
assert(response.output == "hello\n")
```

### TCP Forwarding

```kotlin
dadb.tcpForward(
    hostPort = 7001,
    targetPort = 7001
).use {
    // localhost:7001 is now forwarded to device's 7001 port
    // Do operations that depend on port forwarding
}
```
