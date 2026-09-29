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

The packet bridge preserves separate header/payload transfers, emits host-side
zero-length packets when required, and limits transfers to 16 KiB for older Android
versions. Tests cover fragmented buffered writes, aligned payload reads, invalid
headers, partial writes and close cancellation using a fake USB backend. Actual
USB controller behavior, authorization, throughput and hot unplug require hardware
validation.

On USB, releasing the interface does not necessarily empty its endpoint queues.
During a new handshake Kadb discards up to 64 stale `CLSE`, `OKAY`, or `WRTE` packets
before the new `CNXN`; malformed packets still fail, and TCP/TLS handshakes remain
strict. A hardware reconnect test exposed this case and a regression test covers it.

## Desktop JVM

The JVM artifact uses [Java Does USB 1.3.0](https://github.com/manuelbl/JavaDoesUSB/tree/v1.3.0)
to call native OS APIs directly. No adb executable/server, JNI bundle or libusb install
is needed. This feature is in `2.1.5-usb-SNAPSHOT` on the `usb-support` branch; it is
not in the published `2.1.4` release.

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
The backend supports macOS, Linux and Windows, but only macOS arm64 has been tested
on real hardware in this checkout. Other platforms still need device validation.

### Desktop hardware test

The explicit `desktopUsbSmoke` task is separate from ordinary unit tests. Without a
serial it only lists available ADB-capable devices:

```sh
./gradlew :kadb:desktopUsbSmoke
./gradlew :kadb:desktopUsbSmoke -PusbSerial=YOUR_USB_SERIAL
```

The second command tests shell, interactive stdin, binary output, sync/stat/list,
12 transfer-size boundaries, 32 MiB checksums, concurrent streams, cancellation and
20 close/reopen cycles. Files use a unique `/data/local/tmp/kadb-desktop-usb-*`
directory and are cleaned up. The harness creates its own persistent test key under
`kadb/build/usb-smoke`; approve it on the target. Alternatively, pass an existing host
key explicitly with `-PusbKey=/absolute/path/to/adbkey`; it is read without modification.
The target's `ro.serialno` must match the selected USB serial before any files are created.

Ensure adb or another tool has released the device. If that adb backend supports it,
use `adb -s SERIAL detach` / `attach`; otherwise stop and restore the adb server around
the run, accounting for active wireless debug sessions. Kadb never does this automatically.

An optional `-PusbApk=/absolute/path/to/fixture.apk` also tests installation and uninstall.
Only supply the disposable `com.flyfishxu.kadb.usb.smoke.fixture` APK built by the sibling
WearOS-Toolbox `scripts/build-usb-smoke-fixture.sh`; existing fixture installations are
not replaced. Accept any target-side installation prompt.

### Desktop validation status (2026-09-29)

On macOS arm64 / Java 25 with a USB-connected OPPO PKM110, the initial full run passed
shell, stdin/EOF, binary exec, all sync boundaries/stat/list, 32 MiB three-way SHA-256,
concurrent transfers/probes, error recovery, cancellation and 20 reconnects.
The 32 MiB upload took 1.281 seconds and download 6.004 seconds in that run.
Unit checks passed: 27 JVM tests and 23 Android host tests.

Subsequent runs encountered interface ownership conflicts and device re-enumeration
while Android Studio automatically restarted adb. APK installation was not reached.
Further repeated-reconnect and installation validation requires running without a
competing USB owner. Test artifacts and logs are under `build/reports/desktop-usb/`.
No physical hot-unplug or Windows/Linux hardware validation has been performed.

## Android hardware verification

The sibling WearOS-Toolbox checkout contains an opt-in instrumentation runner that
calls Kadb directly while reusing the application's USB permission and ADB identity.
See its `docs/usb-hardware-testing.md` for commands and fixture generation. Publish
the local snapshot below before building the runner.

On 2026-09-29, an Android USB host connected to a Redmi Note 11 Pro passed shell
stdout/stderr/exit/Unicode, interactive stdin/EOF, 1 MiB binary exec, 12 sync file-size
boundaries, stat/list, 32 MiB push/pull with local and remote SHA-256 agreement,
three concurrent 4 MiB transfers with 20 shell probes, missing-file recovery,
single-stream cancellation, closing blocked readers, and 50 consecutive reconnects.
The 32 MiB transfer took 2.584 seconds to push and 1.924 seconds to pull on that run;
these are single-device observations, not benchmark guarantees.

The first run exposed the stale-stream-packet reconnect bug above. The first run
after updating the snapshot also timed out during initial handshake; its cause was
not established. Subsequent full-suite runs passed, including the 50-reconnect run.
APK installation initially returned `INSTALL_FAILED_USER_RESTRICTED` while the
target's confirmation was not accepted. After accepting its install prompt, USB
installation, package-presence verification, and uninstall all passed using an
empty permission-free fixture APK. All test files and the fixture were removed.
Physical hot unplug, authorization denial and scrcpy are not covered by these results.

Protocol reference: [AOSP USB zero-length packets](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/zero_length_packet.md).
