# Hey Buddy wake word: GitHub flavor only, Android 14+, ONNX Runtime 1.22.0

- The wake word's engine (ONNX Runtime Android 1.22.0, arm64 only) and its three models live in `app/src/wakeword`,
  wired only into the github flavor; `src/main` loads the engine by reflection (as the watch link does). The F-Droid
  APK and `build.sh` builds carry no native library or model, so the F-Droid reproducibility and no-Google-code checks
  are untouched. `build.yml` checks this on the merged manifests and APK contents.
- `WakeService` and the `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` permissions are declared in the
  github-only manifest (`app/src/watchlink/AndroidManifest.xml`).
- Offered on Android 14+ only: below it a background start of a microphone service gives silence, not an error.
- Why and alternatives: `docs/features/032-hey-buddy-wake-word/decisions.md`.
