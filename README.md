# Kadb

A Kotlin Multiplatform library to connect Android devices without ADB server.

Kadb offers a wide range of features, including wireless debugging, apk sideloading, file management, port forwarding,
and shell command execution. Wireless debugging without relying on `adb` binary.

[![Maven Central](https://img.shields.io/maven-central/v/com.flyfishxu/kadb.svg)](https://mvnrepository.com/artifact/com.flyfishxu/kadb)

## Getting Started

Kadb now available on Maven Central:

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:<version>")
}
```

## Features

- Wireless Debugging Support: Say goodbye to cables and the traditional ADB binaries! Kadb enables wireless debugging
  for a hassle-free connection to your Android devices, including Wear OS, Android TV, Android Auto, and even debugging
  directly on the Android device itself.

- Seamless Device Connection: Whether you're working with an emulator or a physical device, Kadb's intuitive APIs make
  connections effortless.

- Secure ADB Connections: With SSL/TLS1.3 support, Kadb ensures your connections are secure, supporting both legacy ADB
  Over WLAN, and new ADB pairing authentication methods.

- Efficient File Management: Push and pull files with ease with Android DocumentFile and Okio, ensuring fast and
  reliable file transfers.

- Enhanced Error Handling: Encounter fewer roadblocks with Kadb's informative exceptions, improving your debugging
  experience.

- Broad Compatibility: Kadb supports Android API level 21 and above, ensuring wide device compatibility. Note: Conscrypt
  library may required on Android Q or earlier devices.

- Kotlin Multiplatform Ready: Kadb is designed to work seamlessly with Compose Desktop projects, allowing you to target
  any JVM based platform with ease.

- Device Discovery and Customization: Easily discover devices and customize Kadb to fit your needs, from generating
  custom certificates to setting ADB client name as you like.

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

<Host: 10.0.0.175; Port: 37755; PairCode: 643102>

```kotlin
Kadb.pair("10.0.0.175", 37755, "643102")
```

*Note: Pair only works when target device running Android 11 and above*

### Discover a Device

The following discovers and returns a connected device or emulator.
If there are multiple it returns the first one
found.

```kotlin
val kadb = Kadb.discover()
if (kadb == null) throw RuntimeException("No adb device found")
```

Use the following API if you want to list all available devices:

```kotlin
val kadbs = Kadb.list()
```

### Connecting to a physical device

*Prerequisite: Connecting to a physical device requires a running adb server. In most cases, this means that you must
have the `adb` binary installed on your machine.*

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

## Acknowledgements

Kadb is based on following projects:

- [Dadb](https://github.com/mobile-dev-inc/dadb): Kadb is based and inspired by Dadb.
  We are grateful for the work done by mobile-dev-inc team.
- [libadb-android](https://github.com/MuntashirAkon/libadb-android): Kadb is inspired by libadb-android and spake2-java
  for SSL connection and ADB Pairing.
