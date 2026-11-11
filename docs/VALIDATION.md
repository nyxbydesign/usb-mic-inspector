# Validation record

Initial candidate validated on 2026-11-11. This record distinguishes build evidence from physical-device acceptance. The exact source commit is supplied in the repository's draft pull request and task handoff.

| Check | Result | What it establishes |
| --- | --- | --- |
| Pure Java descriptor-policy self-test | 218 synthetic assertions passed | Supported fixture allowed; malformed/truncated, mixed/shared, feedback and unsupported layouts refused |
| Android `assembleDebug` and `lintDebug` | Passed; final lint: `No issues found.` | Source compiles/packages against SDK 35 and passes the selected lint checks |
| APK metadata/signature inspection | Passed | Package `dev.usbmic.inspector`, version 1 / `0.1-diagnostic`, min API 34, target API 35; local debug-signed development APK |
| Read-only shell helper javac + D8 | Passed | Helper compiles to a dex JAR; not proof of hidden-API availability or shell privileges on a phone |
| Focused safety source review | No material findings | Explicit initiation/idle confirmation, fresh descriptor gate and independent framework checks precede the sole capture-only claim; no automatic permission-triggered claim |
| `adb devices -l` | No devices attached | No existing connected phone available for testing |
| `adb mdns services` | No services discovered | No already advertised wireless ADB target found |

Build environment: Windows, JDK 21.0.11, Gradle 8.11.1, Android Gradle Plugin 8.7.3, SDK platform/build-tools 35. The wrapper pins the Gradle distribution's official SHA-256.

An initial sandbox build could not access dependencies; an approved standard build path resolved that environment limitation. Lint then exposed API/declaration issues, which were corrected before the passing final build. One narrowly documented `ForegroundServicePermission` detector suppression remains on the connected-device service: this version of lint does not recognize the documented `UsbManager.requestPermission()` alternative. The activity requests USB permission and the service checks it before foreground promotion. No unrelated network/Bluetooth permission or lint baseline was added. [Android foreground-service USB prerequisite](https://developer.android.com/about/versions/14/changes/fgs-types-required#connected-device).

Artifact SHA-256:

```text
app/build/outputs/apk/debug/app-debug.apk
298bf5e3af10bafae375e495871525fd1f05a88148de18e72007ba174aa3c902

shell-helper/build/usb-audio-shell-probe.jar
075fdd9daf1b2f5e57f79fcf8219437843fa3e5e519511b47fc82b00273b388a
```

These generated outputs are excluded from source control. Original build/lint failures and final APK-inspection receipts are retained locally in ignored `evidence/`; phone dumps and personal reports are not part of the public source.

## Physical acceptance still blocked

Pixel 8 Pro is the reported target, not a detected handset. **No actual device model, OS/build, DAC VID:PID, configuration/interface descriptors, route or policy dump was observed.** No APK was installed or opened on a phone or emulator. No microphone recording, playback, USB claim, shell probe execution, Discord test or telephone call occurred.

Unverified: permission-dialog and foreground-service behavior; actual descriptors and gate eligibility; claim success; continuing USB output; default built-in input fallback; release/driver restoration; shell permission/method availability; Discord/call behavior. No end-to-end success or universal no-root fix is claimed.

Use [the minimal phone test](PHONE-TEST.md). Start with inspection and read-only capability evidence. If the claim fails to preserve playback or default input remains USB/silent, stop and replug rather than expanding claims. If the shell has the required routing authority, evaluate an explicitly bounded input-only policy change with restoration in a later device-backed change; that mutation is not implemented here.
