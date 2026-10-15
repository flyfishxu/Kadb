# Platform Notes

This page documents the platform-specific runtime requirements and behavior differences for Kadb.

## Support Matrix

| Area | Android | JVM | iOS 15+ |
| --- | --- | --- | --- |
| Direct `adbd` connection | Yes | Yes | Yes, TCP |
| Shell / shell v2 | Yes | Yes | Yes |
| File transfer | Yes | Yes | Yes, Okio `Path` / `Source` / `Sink` |
| APK install / uninstall | Yes | Yes | Yes |
| TCP forward | Yes | Yes | Yes |
| Wireless pairing | Yes | Yes | Yes |
| Optional mDNS discovery | Yes | Yes | `KadbMdnsIos` |
| USB Host discovery / connection | Yes, `KadbUsb` | Yes, `KadbUsb` (Java 25+) | Not implemented |

The iOS targets are available in the current source tree. Previously published
2.1.5 artifacts do not acquire these targets until a new release is published.

## Pairing Requirements

Pairing has stricter requirements than the rest of the library:

- SPAKE2 support
- TLS 1.3 support
- TLS exporter support required by the pairing flow

Basic connect / shell / sync / install usage does not require the same provider setup as pairing.

## Android

### Runtime baseline

- `minSdk 23`
- direct client features are supported on Android targets
- pairing support depends on TLS provider availability
- optional mDNS discovery is provided by `com.flyfishxu:kadb-mdns`
- mDNS discovery requires an explicit Android `Context` and uses platform `NsdManager`

### USB Host

See [Android USB](usb.md) for permission handling, connection ownership and device requirements.

### Pairing

Android pairing needs:

- SPAKE2 support
- a TLS 1.3-capable provider

In practice:

- Android 9 and newer can usually use the platform provider
- Android 6 to 8 usually need a custom Conscrypt dependency

If TLS 1.3 is unavailable, Kadb fails pairing rather than falling back to an older TLS version.

## JVM

### Runtime baseline

The JVM target supports:

- direct `adbd` connection
- shell
- sync push / pull
- APK install / uninstall
- TCP forward
- wireless pairing
- optional mDNS discovery through `com.flyfishxu:kadb-mdns`

The JVM mDNS implementation uses JmDNS internally and does not require Android concepts such as `Context`.

### USB

Desktop USB uses Java Does USB 1.3.0, with native OS APIs on macOS, Linux, and Windows.
Run on Java 25+ with `--enable-native-access=ALL-UNNAMED` (or grant the named
`net.codecrete.usb` module native access when using the module path).
See [desktop USB](usb.md#desktop-jvm) for permissions, driver requirements and testing.
Only macOS arm64 has been hardware-validated in this checkout.

### Pairing

On JVM, pairing requires a Conscrypt runtime dependency.

Recommended setup:

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:2.1.5")
    implementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
}
```

Without Conscrypt on the classpath, pairing fails with an explicit runtime error.

## Identity and Host Auth

Kadb uses a private-key-first host identity model on every platform:

- the private key is the persisted source of truth
- certificates are generated from that key at runtime
- optional extra private keys can be supplied for AOSP-style multi-key host auth

Further detail: [kadbcert.md](kadbcert.md)

## iOS

Targets: `iosArm64` and `iosSimulatorArm64`, with a minimum deployment target of
15.0. The library remains a synchronous ADB API: run shell, sync and install work
off the application's main thread. Pairing is suspending.
DNS/TCP establishment currently observes coroutine cancellation after the native
connect returns (OS/configured timeout); active TLS handshake cancellation closes
the socket promptly.

```kotlin
import okio.Path.Companion.toPath

Kadb.create("192.168.1.20", 37123).use { client ->
    client.push("/path/to/local.bin".toPath(), "/data/local/tmp/local.bin")
    println(client.shell("id").output)
}
```

For LAN connections the host app must declare `NSLocalNetworkUsageDescription`
and obtain local-network permission. If using `KadbMdnsIos`, also declare these
`NSBonjourServices`: `_adb._tcp`, `_adb-tls-connect._tcp`,
`_adb-tls-pairing._tcp`. Bonjour discovery does not require the restricted
multicast entitlement. Discovery callbacks are scheduled on the main run loop.

TLS and Bouncy Castle are separate layers. Android/JVM still use their existing
Conscrypt/JSSE transport and BC crypto backend. iOS uses a pinned BoringSSL build
for TLS 1.3, the TLS exporter, RSA/X.509 and SPAKE2. It upgrades the existing TCP
socket after ADB's STLS exchange; a generic HTTPS client cannot replace this
transport. Pairing exports 64 bytes with `adb-label` including its terminal NUL,
then binds that material to the pairing code in common code.

The iOS KLIB bundles static native archives; applications consuming the artifact
do not need to install BoringSSL. All upstream symbols use the `KADB_BSSL` prefix
to avoid collisions with other embedded TLS libraries. Retain the bundled
[BoringSSL license](../kadb/third_party/boringssl/LICENSE) when distributing it.

### Build and verify

Xcode with the iOS SDK is required. Gradle Kotlin DSL tasks download a pinned,
SHA-256-checked BoringSSL archive under `kadb/build/native`. If CMake is absent,
the build downloads a checked copy into the same directory; it does not install
system tools. `CMAKE` can point to an existing CMake executable. Python is not
required. The task implementation lives in `kadb/gradle/ios-native.gradle.kts`.

```shell
./gradlew :kadb:linkDebugFrameworkIosArm64 :kadb:linkDebugFrameworkIosSimulatorArm64
# Open an arm64 iOS Simulator first. These tasks use the existing booted device.
./gradlew :kadb:iosSimulatorArm64Test :kadb-mdns:iosSimulatorArm64Test
```

When several simulators are running, set `-PiosSimulatorDevice=<UDID>` for Gradle.
Tests do not boot or shut down simulators. Optional pairing interoperability and
real-device tests remain in `iosTest`; they run only when their explicit
environment variables are supplied. No device credentials are needed for normal
builds or unit tests.

A physical Android device has also been tested from an iPhone 18 Pro / iOS 27
Simulator: pairing, ADB TLS, shell_v2 stdout/stderr/exit status, a 1,048,589-byte
push/pull with matching SHA-256, two reconnects and temporary-file cleanup all
passed. This is simulator-to-Android evidence; physical iPhone execution and
Bonjour permission prompts in a host app remain unverified.

## Shared Code and Platform Backends

`commonMain` owns the ADB protocol, pairing state machine, roles/labels/nonce
counters, PEM encoding, ADB public-key name framing, shell/sync/install logic,
identity lifecycle and message queue. It uses Kotlin APIs and Okio. UTF-8 device
names are encoded by byte length, preserving the terminal NUL even for Chinese
names or emoji.

`jvmAndroidMain` shares the established Java/BC/Conscrypt implementation between
Android and desktop. `iosMain` supplies native crypto/TLS, POSIX sockets,
Foundation blocking primitives and platform integration through the same small
internal boundaries. Blocking locks remain reentrant; a coroutine `Mutex` is not
a replacement for the synchronous queue's condition waits.

Common file APIs now use Okio `Path`, and batch installation accepts
`Collection<Path>`. Existing Android/JVM `File` calls remain source-compatible
through `KadbFileCompatibility`. A JVM bridge preserves the original generated
`Kadb.push$default` descriptor for compiled Kotlin callers, and file metadata
continues to follow symbolic links. `Source`/`Sink` APIs and `OkioFilePrivateKeyStore` remain available on all
three platforms.

## Related Docs

- [Project README](../README.md)
- [mDNS Discovery](mdns.md)
- [KadbCert](kadbcert.md)
