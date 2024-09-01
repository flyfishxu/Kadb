# Kadb
A Kotlin based Android library to connect Android devices directly without ADB binary file.

[![Maven Central](https://img.shields.io/maven-central/v/com.flyfishxu/kadb.svg)](https://mvnrepository.com/artifact/com.flyfishxu/kadb)

```kotlin
dependencies {
  implementation("com.flyfishxu:kadb:<version>")
}
```

## Warning
We are working for make Kadb to support KMP(Kotlin Multiplatform), so some of the APIs in Kadb may changed in the future releases. After our works finish, the APSs will be stable.

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
Kadb.create("localhost", 5555).use { kadb ->
    kadb.install(apkFile)
}
```

*Note: Connect to the odd adb daemon port (5555), not the even emulator console port (5554)*

### Pair with new device

Host: 10.0.0.175;  Port: 37755;  PairCode: 643102

```kotlin
Kadb.pair("10.0.0.175", 37755, "643102")
```

*Note: Pair only works when target device running Android 11 and above*

### Discover a Device

The following discovers and returns a connected device or emulator. If there are multiple it returns the first one found.

```kotlin
val kadb = Kadb.discover()
if (kadb == null) throw RuntimeException("No adb device found")
```

Use the following API if you want to list all available devices:

```kotlin
val kadbs = Kadb.list()
```

### Connecting to a physical device

*Prerequisite: Connecting to a physical device requires a running adb server. In most cases, this means that you must have the `adb` binary installed on your machine.*

The `Kadb.discover()` and `Kadb.list()` methods now both support USB-connected devices.

```kotlin
// Both of these will include any USB-connected devices if they are available
val kadb = Kadb.discover()
val kadbs = Kadb.list()
```

If you'd like to connect directly to a physical device via its serial number. Use the following API:

```kotlin
val kadb = AdbServer.createKadb(
    adbServerHost = "localhost",
    adbServerPort = 5037,
    deviceQuery = "host:transport:${serialNumber}"
)
```

### Install / Uninstall APK

```kotlin
kadb.install(exampleApkFile)
kadb.uninstall("com.example.app")
```

### Push / Pull Files

```kotlin
kadb.push(srcFile, "/data/local/tmp/dst.txt")
kadb.pull(dstFile, "/data/local/tmp/src.txt")
```

### Execute Shell Command

```kotlin
val response = kadb.shell("echo hello")
assert(response.exitCode == 0)
assert(response.output == "hello\n")
```

### TCP Forwarding

```kotlin
kadb.tcpForward(
    hostPort = 7001,
    targetPort = 7001
).use {
    // localhost:7001 is now forwarded to device's 7001 port
    // Do operations that depend on port forwarding
}
```
