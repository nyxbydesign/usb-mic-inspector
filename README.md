# USB Mic Inspector

A standalone Android diagnostic prototype for a USB DAC that advertises a microphone when the desired input is the phone's built-in microphone. **This is not a verified global microphone-routing fix.**

The app lists USB interfaces, alternate settings, endpoints, raw descriptors and Android audio devices. Explicit short probes report the **actual route of this app's active AudioRecord/AudioTrack**, along with capture status. A preferred device being accepted does not prove it was used, and an app-local result does not prove Discord or telephone-call routing.

No microphone samples are saved. Reports are shared only through a deliberate user action. Experiment lifecycle events are retained in bounded process memory and included in reports; they reset when the app process restarts. Probe entries include the current experiment state so a final Release status does not erase evidence of an earlier claim attempt. Review reports before sharing: descriptors, device names and audio addresses can identify hardware. Do not post personal phone diagnostics to a public issue by default.

## Build

Use JDK 21 and Android SDK platform 35. The included Gradle wrapper pins 8.11.1 and verifies its distribution checksum. The app supports Android 14/API 34 and later and has no third-party runtime dependencies.

```powershell
.\gradlew.bat --no-daemon --max-workers=2 assembleDebug lintDebug
.\tests\run-policy-tests.ps1
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. It uses a local Android debug signing key, not a production release identity. Installing and granting microphone/USB access are deliberate user actions; nothing runs against a phone during the build. See [phone testing](docs/PHONE-TEST.md) before using the experiment.

## Experimental USB capture claim

USB permission only enables inspection. A separate confirmation is required for the forced claim. It is offered only for the narrow UAC1 descriptor topology accepted by `UsbCapturePolicy`: one configuration, separate capture and playback interface numbers, consistent alternate settings, audio-control collection and terminal links, and unambiguous isochronous data endpoints. One separate, validated non-boot HID interface is also supported; it is inspected and left untouched. Unknown, mixed, shared, feedback and unsupported descriptor layouts are refused.

The app never claims an audio-control or playback interface, changes the USB configuration, or sends audio-class control transfers. A descriptor-qualified capture interface is **not proof of independent kernel ownership or automatic microphone fallback**. The claim may fail, disrupt USB playback, or leave the phantom input registered in Android policy. Release is best effort; **unplug/replug the DAC may be required**. The experiment is bounded and has an explicit Release control and notification. Read the in-app warning before starting; never begin it during an existing call.

## Findings and current status

- Ordinary `AudioRecord.setPreferredDevice` controls one recorder. `setCommunicationDevice` selects an output and the platform chooses its matching input.
- The Developer option to disable USB audio routing does not provide an input-only switch. It can prevent desired USB playback too.
- An ordinary app cannot obtain signature/privileged audio-policy authority through `pm grant`. However, AOSP Android 16's **shell package itself declares `MODIFY_AUDIO_ROUTING`**. A shell-side input-only policy change is a plausible separate route, subject to the actual device's permission and policy checks. This project includes a read-only shell capability probe; it does not implement that mutation.
- The USB interface claim is an unproven fallback experiment. Linux's normal UAC1 streaming-interface bookkeeping differs from control-interface bookkeeping; neither descriptors nor upstream source establish what the actual Pixel build does.

See [source-backed research](docs/RESEARCH.md) and [validation](docs/VALIDATION.md). Version 0.2 handles the separate HID interface that caused version 0.1 to refuse the user-supplied descriptor topology. The descriptor report has been checked offline; continued physical playback, microphone fallback, restoration, Discord and telephony behavior remain unverified.

Within no-root and no-hardware-change constraints, useful next steps are: run the read-only shell probe; use a target app's independent input/output selector if it actually offers one; submit the captured descriptor and routing evidence privately to the app/OEM when policy cannot be overridden. There is no demonstrated universal no-root fix in this project.

If a claim reports success but the default recorder still uses USB, release/replug and stop repeating that experiment. The next useful evidence is the [read-only shell capability probe](shell-helper/README.md), also provided as a release JAR. It checks the actual shell caller's permissions and API availability without changing audio policy.
