# Read-only ADB audio capability probe

This optional `app_process` helper inventories the existing shell UID's audio
permissions, connected USB audio input/output types and addresses, current audio
mode, and the presence of two framework interfaces. It never changes routing,
claims USB interfaces, records audio, grants permissions, or writes settings.
It has **no mutation command**.

The helper obtains a `com.android.shell` package context and verifies that its
application UID matches shell UID 2000. It reports both the context package and
its attribution package: a derived context may retain its parent's operation
package. The selected AOSP inventory APIs do not send package attribution to
AudioService (`getDevices` lists native audio ports; the mode and recording-list
queries use Binder methods without a package argument). This does not qualify
that context for other, attributed APIs.

It targets Android 12/API 31 and newer. Reflection availability and permissions
depend on the actual OS build. It refuses to run under a UID other than the
ordinary ADB shell UID 2000. A denial or missing interface is a diagnostic result;
do not change security or hidden-API settings to get past it.

## Build and run explicitly

With JDK 17+ and Android SDK platform/build-tools 35 installed:

```powershell
.\shell-helper\build.ps1
```

On an already authorized ADB connection, with the DAC attached, run these commands
only when you choose to collect this read-only inventory:

```powershell
adb push .\shell-helper\build\usb-audio-shell-probe.jar /data/local/tmp/usb-audio-shell-probe.jar
adb shell 'CLASSPATH=/data/local/tmp/usb-audio-shell-probe.jar app_process /system/bin dev.usbmic.inspector.shell.ShellAudioProbe probe'
```

Wireless ADB lets the DAC remain in the phone's USB-C port. This project does not
enable debugging, pair a device, or start a probe automatically. LADB can run the
same device-side `CLASSPATH=... app_process ...` command once the jar has been
placed in `/data/local/tmp` through an authorized transfer.

Review output before sharing it privately. USB addresses are needed to identify
the exact policy port; do not publish personal diagnostic output. The helper
omits device serial numbers, product names, recording identities and full dumps.
`visible_active_recording_count=0` is not a guarantee that no call or recording is
active: visibility may be limited. The helper performs only reads in every case.

## What the result establishes

AOSP's [Android 16 shell manifest](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/packages/Shell/AndroidManifest.xml)
declares `MODIFY_AUDIO_ROUTING`. The actual phone may differ. The hidden
[`AudioService.setWiredDeviceConnectionState`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/audio/AudioService.java)
checks that permission and accepts role-specific device attributes. The
[`AudioDeviceAttributes(AudioDeviceInfo)` constructor](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/media/java/android/media/AudioDeviceAttributes.java)
preserves the selected port's role, type and address.

Together these make an input-only shell policy experiment worth evaluating after
reading the actual phone's capabilities and policy. They do **not** demonstrate a
working mic override. A future mutation needs an exact input target, explicit idle
confirmation, a bounded lifetime, an evidence-backed restoration path and checks
of continued USB playback plus actual Discord/call microphone use. This helper
introspects the method and constructor without invoking either. It does not
implement that mutation or use hardcoded Binder transaction numbers.

Exit status is 0 for a complete read-only probe, 1 for a partial/blocked probe, and
2 for invalid usage, an unexpected UID or an unsupported Android version. A
complete probe can legitimately report a permission as `DENIED`.
