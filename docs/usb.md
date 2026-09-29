# Android USB Host

The Android target exposes `com.flyfishxu.kadb.usb.KadbUsb` for direct ADB over USB.
It uses Android's USB Host API, without a bundled adb executable or native USB library.
Desktop JVM USB is not implemented.

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

## Hardware verification

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

## Local integration with WearOS-Toolbox

From this repository, publish the Android snapshot into the application's existing
workspace Maven repository:

```sh
./gradlew :kadb:publishAndroidPublicationToWorkspaceRepository \
  -PworkspaceMavenRepository="$PWD/../WearOS-Toolbox/.local-maven" \
  -PsignAllPublications=false
```

The feature branch uses `com.flyfishxu:kadb-android:2.1.5-usb-SNAPSHOT` so it does not
replace the previous connection-stability snapshot. The local repository is ignored
by Git; another checkout or CI must publish the same snapshot before building the
application, or switch to a published release that includes USB support.

Protocol reference: [AOSP USB zero-length packets](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/zero_length_packet.md).
