# Kadb

[![Maven Central](https://img.shields.io/maven-central/v/com.flyfishxu/kadb.svg)](https://central.sonatype.com/artifact/com.flyfishxu/kadb)

Kadb is a Kotlin Multiplatform ADB client library for talking directly to `adbd`.

It is intended for apps and tools that need shell, sync, install, pairing, or port forwarding without embedding the full `adb` CLI or server stack.

[Platform Notes](docs/platform.md) · [Host Identity](docs/kadbcert.md) · [Docs Index](docs/README.md)

## Overview

- Direct Kotlin API for `adbd`
- Android and JVM targets
- Wireless pairing, shell, file transfer, install, and TCP forwarding
- AOSP-aligned host behavior where practical

Kadb is not a full adb server replacement. USB discovery, transport brokering, and server-style device tracking are out of scope.

## Installation

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:2.1.1")
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

## API Overview

| Capability | API |
| --- | --- |
| Connect to `adbd` | `Kadb.create(...)` |
| Wireless pairing | `Kadb.pair(...)` |
| Shell | `shell(...)`, `openShell()`, `openPtyShellSession()` |
| File transfer | `push(...)`, `pull(...)`, `openSync()` |
| APK install | `install(...)`, `installMultiple(...)`, `uninstall(...)` |
| Port forwarding | `tcpForward(...)` |
| Transport reuse | `resetConnection()` |

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
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
}
```

More detail: [docs/platform.md](docs/platform.md)

## Scope and Limitations

- Kadb is a direct client library, not a full adb server
- USB transport discovery still requires external tooling

## Documentation

- [Documentation Index](docs/README.md)
- [Platform Notes](docs/platform.md)
- [KadbCert](docs/kadbcert.md)

## Acknowledgements

- [Dadb](https://github.com/mobile-dev-inc/dadb)
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)
- [spake2-java](https://github.com/Flyfish233/spake2-java)
