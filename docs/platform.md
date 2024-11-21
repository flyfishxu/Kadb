# Platform

Kadb requires specific libraries that depends on device platform.

`libspake` and `conscrypt` is required if planning to use `adb pair`.

It will not affect other features, such as connecting or use legacy pairing.

## Android

### LibSpake

In Android build of Kadb, we use pre-built `libspake.so` to handle ADB Paring on Android 9 or above.

### Conscrypt

#### SDK 28 or above

On Android 9 or above, on-device `Conscrypt` supported TLS 1.3, no need to package your own Conscrypt.

`libspake` now supported 16kB page-size on Android 15 or above.

#### SDK 23 ~ 27

Download: https://github.com/google/conscrypt

## JVM (OpenJDK)

### Conscrypt

Conscrypt is required if planning to use `adb pair`. Download: https://github.com/google/conscrypt

### LibSpake

On JVM, Kadb uses pure Java implementation of `libspake`.