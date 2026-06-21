# mDNS Discovery

Kadb mDNS discovery is available as the optional `com.flyfishxu:kadb-mdns` artifact.

The module discovers ADB-related mDNS services only. It does not open ADB connections, perform pairing, or manage device authorization. Consumers decide how to use discovered endpoints with `Kadb.create(host, port)` or `Kadb.pair(host, port, code)`.

## Installation

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb-mdns:2.1.2")
}
```

Use the core Kadb artifact separately when you also need ADB connections:

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:2.1.2")
    implementation("com.flyfishxu:kadb-mdns:2.1.2")
}
```

## Service Types

The default configuration searches for:

- `_adb._tcp`
- `_adb-tls-connect._tcp`
- `_adb-tls-pairing._tcp`

`_adb._tcp` and `_adb-tls-connect._tcp` are exposed as connect endpoints. `_adb-tls-pairing._tcp` is exposed as pairing endpoints.

## Android

Android discovery requires an explicit `Context`:

```kotlin
val mdns = KadbMdnsAndroid(context)
mdns.start()
```

The Android implementation uses platform `NsdManager`, stores `context.applicationContext`, and supports API 23+. Android API 34 and newer use `NsdManager.ServiceInfoCallback`; older versions use `resolveService`.

## JVM

JVM discovery does not require Android concepts:

```kotlin
val mdns = KadbMdnsJvm()
mdns.start()
```

The JVM implementation uses JmDNS internally. It attempts to create discovery backends for eligible active network interfaces and falls back to a default JmDNS instance if interface enumeration cannot provide a usable address.

## Lifecycle

`KadbMdns` exposes a `StateFlow<MdnsDiscoveryState>`:

```kotlin
mdns.state.collect { state ->
    val target = state.connectDevices.firstOrNull()
    if (target != null) {
        // Kadb.create(target.host, target.port)
    }
}
```

Call `start()` to begin discovery. Call `stop()` or `close()` to stop listeners and clear state.

## Notes

- No default logging is emitted by the module.
- Discovery data is best-effort and network-dependent.
- The library trusts mDNS host/port data; connection failures should be handled by the caller.
- USB discovery remains out of scope.
