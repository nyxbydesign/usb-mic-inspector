# Contributing

This standalone Android project is a diagnostic prototype. Open changes as draft pull requests against `main` until physical-device acceptance is available.

Run `gradle --no-daemon --max-workers=2 assembleDebug lintDebug` with JDK 21 and Android SDK 35, and run `tests/run-policy-tests.ps1`. The pure Java policy tests use synthetic descriptors and require no phone. Do not count those tests as evidence of real microphone or playback routing.

Keep USB interface claims fail-closed and explicitly initiated. Never claim a control, output, mixed or ambiguous interface. Do not start recording, playback or a USB claim automatically. Preserve explicit release controls and recovery warnings. Do not relax safety gates to enable an unverified DAC.

Document primary sources and separate upstream behavior, synthetic results, app-local observations and target-app acceptance. Before adding a shell-policy mutation, verify the actual phone's caller permission, API shape and routing inventory, and design an explicit restoration path.

Do not commit credentials, keystores, machine paths, device serials, phone dumps, personal reports or generated build output. Review/redact diagnostics before sharing. Keep changes limited to this project.
