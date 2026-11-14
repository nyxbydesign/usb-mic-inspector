# Validation record

This record distinguishes build evidence, offline descriptor checks and physical-device acceptance. The exact source commits are supplied in the repository's pull requests and release metadata.

## Version 0.2 validation (2026-11-14)

- Reproduced the original HID rejection using the supplied descriptor bytes against the committed version 0.1 parser. The input length and SHA-256 matched the supplied report.
- Passed 285 descriptor-policy assertions, including the anonymized composite regression selecting capture interface 1, HID-first ordering, existing audio safety tests, and malformed, shared, duplicate and truncated HID cases. AudioControl, playback and HID interfaces are never selected for the claim.
- Passed `assembleDebug` and `lintDebug`; final lint reports `No issues found.` The existing documented connected-device USB prerequisite detector suppression remains unchanged.
- Verified the development APK's v2 signature and exact certificate match with version 0.1 for an in-place upgrade. Metadata: package `dev.usbmic.inspector`, version code 2, version `0.2-diagnostic`, min API 34, target/compile API 35. Artifact size: 68,861 bytes.
- Reviewed the narrow parser changes: optional HID descriptors are validated independently; interface-role and endpoint-address conflicts fail closed; all prior capture, playback and audio-control requirements remain in place. The service and interface-claim operation are unchanged.

Version 0.2 APK SHA-256:

```text
3664a14711c3b16c5fbc8005ad0c4a271c6490054b526bc0460554413b36da77
```

Build environment is unchanged from version 0.1 below. No device discovery, installation, runtime audio probe, USB claim or call was performed for this update. Offline descriptor acceptance does not establish working playback, microphone fallback or restoration. Version 0.2 build, metadata and signature receipts are retained locally under ignored `evidence/0.2/`.

## Version 0.1 historical validation (2026-11-11)

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

Version 0.1 artifact SHA-256 (the app output path is reused by subsequent builds):

```text
app/build/outputs/apk/debug/app-debug.apk
298bf5e3af10bafae375e495871525fd1f05a88148de18e72007ba174aa3c902

shell-helper/build/usb-audio-shell-probe.jar
075fdd9daf1b2f5e57f79fcf8219437843fa3e5e519511b47fc82b00273b388a
```

These generated outputs are excluded from source control. Original build/lint failures and final APK-inspection receipts are retained locally in ignored `evidence/`; phone dumps and personal reports are not part of the public source.

## Physical routing acceptance still pending

No phone or emulator was available to the developer during initial validation. A subsequent user-supplied version 0.1 report on 2026-11-14 documents app startup, Android audio-device inventory and USB descriptor inspection on a phone. The exact descriptor bytes were reconciled with the report's SHA-256; the original parser's HID rejection was reproduced offline. The public regression fixture retains this topology with anonymized vendor/product and string-index fields. Private phone build details and diagnostic reports are excluded from publication.

The supplied report contains no active microphone or playback probe, USB claim, restoration result, shell probe, Discord test or telephone-call test. Those behaviors remain unverified. Descriptor eligibility checked offline is not evidence of successful kernel detach or microphone fallback. No end-to-end success or universal no-root fix is claimed.

Use [the minimal phone test](PHONE-TEST.md). Start with inspection and read-only capability evidence. If the claim fails to preserve playback or default input remains USB/silent, stop and replug rather than expanding claims. If the shell has the required routing authority, evaluate an explicitly bounded input-only policy change with restoration in a later device-backed change; that mutation is not implemented here.
