# Kadb

[![Maven Central](https://img.shields.io/maven-central/v/com.flyfishxu/kadb.svg)](https://central.sonatype.com/artifact/com.flyfishxu/kadb)

Kadb is a Kotlin Multiplatform ADB client library for talking directly to `adbd`.

It is intended for apps and tools that need shell, sync, install, pairing, or port forwarding without embedding the full `adb` CLI or server stack.

## Overview

- Direct Kotlin API for `adbd`
- Android, JVM, and iOS targets (iOS support is currently in source; see [platform notes](docs/platform.md#ios))
- Android and desktop JVM USB connections, wireless pairing, shell, file transfer, install, and TCP forwarding
- AOSP-aligned host behavior where practical

## Powered by Kadb

Kadb powers [WearOS Toolbox](https://wearosbox.com/), an Android smartwatch toolbox
for connecting to watches, managing apps, and transferring files.
Both Kadb and WearOS Toolbox are developed by Flyfish Studio.

## Installation

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:2.1.5")
}
```

Optional mDNS discovery:

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb-mdns:2.1.5")
}
```

## Quick Start

Connect to an existing device transport:

```kotlin
Kadb.create("127.0.0.1", 5555).use { kadb ->
    val response = kadb.shell("echo hello")
    check(response.exitCode == 0)
    check(response.output == "hello\n")
}
```

Pair with a new Android 11+ device:

```kotlin
Kadb.pair("10.0.0.175", 37755, "643102")
```

Discover wireless debugging endpoints with the optional mDNS artifact:

```kotlin
val mdns = KadbMdnsAndroid(context)
mdns.start()
```

```kotlin
val mdns = KadbMdnsJvm()
mdns.start()
```

Discovered endpoints can be passed to `Kadb.create(host, port)` or `Kadb.pair(host, port, code)`.

## API Overview

| Capability | API |
| --- | --- |
| Connect to `adbd` over TCP | `Kadb.create(...)` |
| USB discovery / connection | Platform-specific `KadbUsb.devices(...)`, `KadbUsb.create(...)` |
| Wireless pairing | `Kadb.pair(...)` |
| Shell | `shell(...)`, `openShell()`, `openPtyShellSession()` |
| File transfer | `push(...)`, `pull(...)`, `openSync()` |
| APK install | `install(...)`, `installMultiple(...)`, `uninstall(...)` |
| Port forwarding | `tcpForward(...)` |
| Transport reuse | `resetConnection()` |
| Optional mDNS discovery | `KadbMdnsAndroid`, `KadbMdnsJvm` |

## Examples

Install an APK:

```kotlin
Kadb.create("127.0.0.1", 5555).use { kadb ->
    kadb.install(apkFile)
}
```

Push a file:

```kotlin
Kadb.create("127.0.0.1", 5555).use { kadb ->
    kadb.push(localFile, "/data/local/tmp/remote.txt")
}
```

Forward a TCP port:

```kotlin
Kadb.create("127.0.0.1", 5555).tcpForward(
    hostPort = 7001,
    targetPort = 7001
).use {
    // localhost:7001 now forwards to the device's port 7001
}
```

## Platform and Pairing Notes

- Android target support starts at `minSdk 23`
- Basic connect / shell / sync / install flows do not require the full `adb` binary
- Pairing has stricter requirements than ordinary client operations
- JVM pairing requires `conscrypt-openjdk-uber`
- Android 6 to 8 usually need a custom Conscrypt dependency for pairing

Example JVM pairing dependency:

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:2.1.5")
    implementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
}
```

## Scope and Limitations

- Kadb is a direct client library; transport brokering and adb server-style device tracking are out of scope.
- USB connections are available on Android and desktop JVM. A device's USB interface must be released by other tools before Kadb can claim it.

## Documentation

- [USB connections](docs/usb.md) — Android and desktop setup, permissions, and connection lifecycle
- [Platform notes](docs/platform.md) — runtime requirements and pairing dependencies
- [mDNS discovery](docs/mdns.md) — discovering wireless debugging endpoints
- [Host identity](docs/kadbcert.md) — managing and persisting ADB credentials
- [Security policy](SECURITY.md) — reporting vulnerabilities privately

## Acknowledgements

- [Dadb](https://github.com/mobile-dev-inc/dadb)
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)
- [spake2-java](https://github.com/Flyfish233/spake2-java)
