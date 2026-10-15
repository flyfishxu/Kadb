# USB connections

## Android USB Host

The Android target exposes `com.flyfishxu.kadb.usb.KadbUsb` for direct ADB over USB.
It uses Android's USB Host API, without a bundled adb executable or native USB library.
The desktop JVM target has its own context-free API, described below.

The controlling Android device must support USB Host/OTG. Use a data-capable cable;
the target must expose an ADB interface and have USB debugging enabled.

Declare the optional feature in the application manifest:

```xml
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

Enumerate and let the user choose a device:

```kotlin
val manager = context.getSystemService(UsbManager::class.java)
val devices = KadbUsb.devices(manager)
```

Use `UsbManager.requestPermission(device, pendingIntent)` if permission is missing.
Handle the result in the application and verify `manager.hasPermission(device)`.
This permission is separate from the target device's “Allow USB debugging” prompt.
Kadb does not display either prompt itself and does not use wireless pairing for USB.

After permission is granted, perform blocking operations on an I/O dispatcher:

```kotlin
withContext(Dispatchers.IO) {
    KadbUsb.create(context, device).use { kadb ->
        val response = kadb.shell("echo hello")
        check(response.exitCode == 0)
    }
}
```

`create` is lazy: the first command opens the interface and authenticates using the
existing `KadbCert` identity. Shell, sync, installation and TCP forwarding APIs are
the same as for a network connection. TCP keepalive options do not apply to USB.

Keep **one Kadb instance per physical USB device** and multiplex operations through
its independent ADB streams. Do not create separate clients for health probes,
installation progress or screen streaming. Close an operation's stream when it
ends; close Kadb only when the entire device session ends.

The transport cancels pending reads on close/detach. The application must bound
initial authorization waiting and close the pending Kadb when the user cancels;
`withTimeout` alone cannot interrupt blocking Kadb calls. Individual USB writes
have a bounded timeout. After unplug/replug, enumerate, select and check permission
again; USB bus addresses must not be persisted as device identity.

## Desktop JVM

The JVM artifact uses [Java Does USB 1.3.0](https://github.com/manuelbl/JavaDoesUSB/tree/v1.3.0)
to call native OS APIs directly. No adb executable/server, JNI bundle or libusb install
is needed.

Use Java 25+ and grant native access with `--enable-native-access=ALL-UNNAMED` on the
classpath, or `--enable-native-access=net.codecrete.usb` on the module path. Gradle
applications/Compose Desktop distributions must pass that option to their runtime JVM.

```kotlin
import com.flyfishxu.kadb.usb.KadbUsb

val devices = KadbUsb.devices()
// Display productName, manufacturer, serialNumber, vendorId and productId to the user.
val selected = devices.single { it.serialNumber == expectedSerial }
KadbUsb.create(selected).use { kadb ->
    check(kadb.shell("echo desktop-usb").output.trim() == "desktop-usb")
}
```

`KadbUsbDevice` is a handle for the current attachment. Re-enumerate after unplug/replug;
`isConnected` describes physical attachment, not ADB authorization. Discovery only
returns interfaces matching ADB's `ff/42/01` class/subclass/protocol with bulk IN/OUT
endpoints. Opening is lazy and claims the selected ADB interface, including an alternate
setting when needed. Closing aborts transfers and releases the interface/device.
Keep one client per device; use separate streams for concurrent operations.

Configure a persistent `KadbCert` identity as documented in [Host Identity](kadbcert.md).
The device may prompt to authorize that identity. There is no Android host permission
dialog on desktop. The library does not stop other USB owners or change OS drivers.

- macOS: the interface must be available; a running adb server can own it exclusively.
- Linux: needs libudev/systemd and USB device permissions (typically a udev rule).
- Windows: requires a compatible WinUSB driver for the ADB interface.

These platform requirements follow the [backend documentation](https://github.com/manuelbl/JavaDoesUSB/tree/v1.3.0#platform-specific-considerations).

### Desktop hardware test

The explicit `desktopUsbSmoke` task is separate from ordinary unit tests. Without a
serial it only lists available ADB-capable devices:

```sh
./gradlew :kadb:desktopUsbSmoke
./gradlew :kadb:desktopUsbSmoke -PusbSerial=YOUR_USB_SERIAL
```

The second command checks shell, file integrity, concurrent streams, cancellation and
reconnection. It creates temporary files on the target and removes them during cleanup.
The harness stores its test identity under `kadb/build/usb-smoke`; approve it on the
target when prompted. To reuse an existing identity without modifying its key file,
pass `-PusbKey=/absolute/path/to/adbkey`.

Ensure adb or another tool has released the device. If that adb backend supports it,
use `adb -s SERIAL detach` / `attach`; otherwise stop and restore the adb server around
the run, accounting for active wireless debug sessions. Kadb never does this automatically.

## Validation limits

Basic USB operations have been exercised on Android and macOS arm64. Windows and
Linux still need hardware validation. Desktop APK installation, physical hot unplug,
initial authorization denial and scrcpy have not been validated end to end.
An initial-handshake timeout was observed on Android and remains undiagnosed.
