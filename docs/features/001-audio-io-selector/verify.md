# Verification: 001-audio-io-selector

Verifier: orchestrator (Opus 5.5), 2026-11-15. A full fork build can't run next to the official Buddy (same local
ports), so the routing was verified with a probe app (`/root/.buddy/work/audioroute-probe`, not committed): it
contains `AudioRoute.java` copied unchanged from this branch, with stand-ins for Prefs/Voice/CodexLive that hook it
exactly like the branch (Voice.speak/say/listen, a simulated live call that sets MODE_IN_COMMUNICATION like Chromium).
Phone: S21 FE (SM-G990W), Galaxy Watch4 connected.

| Done-when item | Evidence | Result |
|---|---|---|
| 1. Settings row lists Auto + devices | Probe lists Auto, Phone, Speaker from `AudioRoute.choices()`. Watch not listed: see note | partial |
| 2. Quick switch | Probe's quick-switch button (same code as the chip) used by Nicholas; Settings/VoiceBar UI itself unseen on device | partial |
| 3. Routing applies | Nicholas: Phone and Speaker "good" for read aloud / sample; dictation heard "testing testing can you hear me" | pass (phone/speaker) |
| 4. Remembered, Auto fallback | Code reviewed (Codex r1-r2); not exercised on device | code only |
| 5. Auto unchanged, cleanup | After tests: `mode: NORMAL`, Buddy route off, comm device back to earpiece (probe status line) | pass |
| 6. CI | Fork run 43684188418: helper-tests ✓, assembleGithubRelease/assembleFdroidRelease ✓ (lintVital); only "F-Droid APK is reproducible" fails because the fork has no signing secrets (unsigned APK has no META-INF), same on 002 | pass (env limit noted) |

Note (watch), checked on device 2026-11-15: Settings → Bluetooth shows "Nicholas's Watch4 · Connected for calls",
yet Android's audio policy lists no Bluetooth SCO device at all (`dumpsys media.audio_policy`, available outputs:
Earpiece, Speaker, Telephony Tx; inputs: built-in mics only), and `getAvailableCommunicationDevices()` returns only
Phone and Speaker. Samsung handles watch calls through its own call relay, not as a standard hands-free audio device,
so no app (Buddy included) can route its own audio to the Watch4. Platform limit, not a code gap: any real headset
(e.g. Buds2, a car kit) that Android exposes appears in the list automatically (key = type:name).

Not exercised on device (needs the fork installed in place of the official app): the Settings row and voice-bar chip
UI themselves, the live-voice WebView path, reconnect/fallback, AutoPause. These are covered by plan + 2 Codex code
reviews (isolated harness for the Auto transition) and should be re-checked when Nicholas switches to the fork.

VERDICT: SHIP (with the watch limitation documented; device checks of the UI pending the switch to the fork)
