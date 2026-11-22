# Verification: 032-hey-buddy-wake-word

Verifier: orchestrator (claude-opus-5-5), 2026-11-22. CI: Build workflow green on a3e161a (run 45004142469) and on
b438bdc (run 45003087939, before the round 3 fixes); final head: see the PR's checks. No JVM or Gradle runs on the
phone, so unit tests are CI-only. Device behaviour can't be checked before release (the fork can't be installed beside
the live Buddy; the probe was dropped by the orchestrator ruling in decisions.md), so device rows are **pending**
until the release is on the phone.

| Done-when item | Evidence | Result |
|---|---|---|
| Foreground service listens on-device, model in the APK, persistent notification | `WakeService` (microphone FGS, `foregroundServiceType="microphone"`, `watchlink/AndroidManifest.xml:6-14`), notification channel `wake`; models + `SHA256SUMS` in `app/src/wakeword/assets/wakeword`; CI `OnnxWakeEngineTest` (real models: clean 0.99, augmented 0.17, sound-alike 0.057; the threshold is now the sensitivity's, 034), `WakeIdleTest` (nothing reaches `MicServer` before a wake; pre-roll ≤ 1.5 s after); `build.yml` APK/manifest flavor check | pass (CI); device P1 pending |
| One breath sends the request without the phrase; "hey buddy" alone listens | `WakeWord.strip`, `WakeWord.Turn` (AGAIN once, then END), `WakeLoop` HOLD/FEED; `MicServer.adopt` hand-off with pre-roll; fed Phone path via `EXTRA_AUDIO_SOURCE` with the unfed fallback (Q1 = a); CI `WakeWordTest`, `WakeLoopTest`, `VoiceWindowTest` | pass (CI); device T2 pending |
| Follow-up goes to the same chat as Talk | `ChatHub.sendTalk` is the one destination for Talk (`Bubble.java:1679`) and wake (`WakeWord.java:235`) | pass (code); device T3 pending |
| "hey buddy, stop" cuts speech ≤ ~1 s; AEC while speaking | `Voice.stopSpeaking()` on the wake itself (`WakeWord.java:156`); stop swallowed only when playback overlapped (`overlapped`, `isStop`); `WakeMic` opens `VOICE_COMMUNICATION` + `AcousticEchoCanceler` while speaking, logged fallback; CI `WakeWordTest` | pass (CI); device P2/T4 pending |
| Pauses for calls and other recorders, resumes; Buddy's paths don't fight | `WakeWord.state` table (call, foreign recording, OFF beat TURN; 1 s resume), `cancelTurn`; confirmed detector release before Talk/dictation (`WakeWord.release`) and phone Live (round 3 F4); `MicServer` capture ids, socket reader bound to its capture; CI `WakeWordTest`, `MicServerTest`, `VoiceWindowTest` | pass (CI); device T5 pending |
| On by default; Settings switch; quick toggle; survives restart/reboot | `Prefs.WAKE_WORD_DEFAULT = true`; Settings → Voice switch (`SetupActivity`); bubble hold menu "Hey Buddy off/on" (`Bubble.java:1537`) and notification **Turn off**; `WakeWord.ensure` from `BuddyService` connect and every activity resume, tap-to-start fallback; CI `SettingsCoverageTest` default-on | pass (CI/code); device T6 (reboot path) pending |
| `buddy_settings` reads/changes it | `hey_buddy` in `SettingsTool` (`:347`), refuses "on" where unavailable; CI `SettingsCoverageTest` | pass (CI); device T7 pending |
| Battery impact in user docs and feature folder | `docs/GUIDE.md:49` (Battery, marked estimate, drivers), `battery.md` (sourced estimate, method for the measured hour) | pass (estimate); measured P4 pending |
| Unit tests for pure logic; CI green | 84 test methods across `WakeWordTest`, `WakeLoopTest`, `MicServerTest`, `VoiceWindowTest`, `WakeFeaturesTest`, `SpokenAskTest`, `CodexDictationTest`, `OnnxWakeEngineTest`, `WakeIdleTest` + settings case; Build green incl. F-Droid reproducibility | pass |

Diff scope: 48 files outside the feature folder, all named by the plan (engine, models, service, voice hand-off,
settings, toggle, docs, CI check); no probe leftovers. Open review findings: plan round 3 and code rounds 3-4 ruled
in decisions.md (code round 3 F3 accepted as a rare race after its fix was reverted).

## Post-release device checks (pending, run on the phone after the release is installed)
- **P1 / threshold first:** 10 "hey buddy" at arm's length (≥ 8); 30 min TV/podcast (≤ 2 false wakes; `logcat -s HeyBuddy`
  shows each wake and near miss with its scores; tune with the sensitivity (034)); screen off and unplugged 5 min, then 5
  "hey buddy" (≥ 4).
- **P2:** while Buddy speaks at 60% volume, 10 "hey buddy" (≥ 7); `logcat -s BuddyWake` shows the source and whether
  the echo canceller was enabled; Buddy's own speech doesn't trigger it.
- **P6 / T2:** with `voice_input` = phone and = claude: "hey buddy what's the weather like" in one breath ×5, and
  "hey buddy", 3 s pause, request; the log shows whether Phone stayed fed or fell back to unfed.
- **T3:** Talk "remember the word pineapple", then "hey buddy, what word did I ask you to remember" → same chat.
- **T4:** stopwatch end of "buddy" → silence ≤ 1 s ×5; "stop" not sent; "hey buddy, cancel" 2 s after a reply → sent.
- **T5:** a phone call and Samsung Voice Recorder pause and resume it; recorder started mid-turn cancels only that
  turn; Terminal `arecord | head -c 96000 > /tmp/t.pcm` during a Phone wake turn holds no "hey buddy"; Talk,
  dictation and a Live call work as before.
- **T6:** fresh update → listening (or the tap notification); switch and hold menu stay in step; reboot → log says
  whether the accessibility start was allowed; switch off + reboot → nothing; mic permission revoked, then granted
  from Settings → Voice → works without opening the home screen.
- **T7:** `buddy_settings hey_buddy` reads and changes with a card.
- **P4:** `dumpsys batterystats --reset`, 1 h unplugged screen off, listening vs not; figure replaces the estimate
  in `battery.md` and the guide.

VERDICT: SHIP (device checks above pending after release)
