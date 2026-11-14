# Minimal physical-phone test

No physical-phone verification has been performed. Do not start with a real call. The goal is to learn whether a route works, not to force a positive result.

1. When idle, install `app-debug.apk` and open USB Mic Inspector. Use an already authorized wireless ADB connection if ADB is needed while the DAC occupies the phone's USB port. Do not change security settings or authorize a workstation solely because a script asks.
2. Connect the existing DAC/headphones. Refresh, select the intended USB device, and request USB inspection access. Permission approval must only inspect; it must not claim anything. Save/review the diagnostic report privately. Record Android version/build, VID:PID, configurations/interfaces/alternate settings/endpoints, gate decision, Android USB input/output type/address. Redact personal identifiers before sharing.
3. Explicitly run the short USB playback probe at a comfortable system volume; confirm sound comes from the wired headphones and record its actual AudioTrack route. Run the default microphone probe, then the built-in preference probe. Speak near the phone; record the actual AudioRecord route, read status and silencing. Built-in preference success is only an app-local control case. A displayed device route plus silence is not a successful microphone test.
4. Optionally run the read-only `shell-helper` probe from the existing ADB/LADB shell. It does not modify policy. Save whether UID, permission and hidden method are available. Stop on a denial. Do not try a guessed service transaction or permission grant.
5. Only if the app offers a descriptor-qualified capture interface, close other recording apps and ensure no call is active. Read the warning. Explicitly confirm the idle state and start the capture claim. If planning a short subsequent communication test, use its separate opt-in. The bounded test automatically expires; complete any intended test before the countdown expires. A claim failure is not proof of a routing fix.
6. Immediately repeat USB playback and default-input probes. The desired observation is built-in input **without a preferred-device override** and continuing wired USB output. If playback fails, input stays USB, recording errors/silence occur, or the system becomes unstable: release, then unplug/replug if needed; stop this experiment.
7. Only after the prior checks pass, stop this app's recording/playback probes. With explicit communication-test opt-in and sufficient time left, manually use a private Discord mic-test facility or willing test participant. Verify speech near the phone is heard and output remains in wired headphones. Do not automatically initiate a call. Test telephony separately only in a deliberately arranged nonessential test call. Record each app, version and outcome separately. Never report an app-local route as proof of either.
8. End the test before timeout; press Release in the app or foreground notification. Recheck normal playback/input. If the original routing is not restored, physically unplug/replug the DAC. Force-stop/process death is not proof of driver reattachment.

If the strict gate refuses the DAC, do not edit the gate merely to enable a trial. Review the descriptors. The shell-policy route may be a better next investigation if its read-only probe confirms capability; its mutation/restoration flow is intentionally not implemented without device evidence.

If a capture claim reports success but a subsequent default microphone probe still uses USB, stop that experiment and release/replug. A successful claim does not notify Android to remove the input policy port. Do not repeat the claim simply to get another result. Collect the read-only shell capability report next; it checks existing permissions and method availability without changing routing. Version 0.3 retains timestamped claim/release history in process memory and includes contemporaneous experiment state in local probe entries, so later reports can distinguish a successful claim, a refused attempt and release without a held interface.

Optional private ADB observations, run manually against the intended phone:

```text
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell dumpsys usb
adb shell dumpsys audio
adb shell dumpsys media.audio_policy
adb shell dumpsys package com.android.shell
adb shell cmd audio help
```

Dumps can contain device addresses, app activity and personal data. Keep them local; retain only the relevant redacted observations in a shared report. Do not change settings or policy with these commands.
