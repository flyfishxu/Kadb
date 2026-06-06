# Platform Notes

This page documents the platform-specific runtime requirements and behavior differences for Kadb.

## Support Matrix

| Area | Android | JVM |
| --- | --- | --- |
| Direct `adbd` connection | Yes | Yes |
| Shell / shell v2 | Yes | Yes |
| File transfer | Yes | Yes |
| APK install / uninstall | Yes | Yes |
| TCP forward | Yes | Yes |
| Wireless pairing | Yes | Yes |
| Optional mDNS discovery | Yes | Yes |
| USB discovery | No | No |

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

### Pairing

On JVM, pairing requires a Conscrypt runtime dependency.

Recommended setup:

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:2.1.2")
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

## Related Docs

- [Project README](../README.md)
- [mDNS Discovery](mdns.md)
- [KadbCert](kadbcert.md)
