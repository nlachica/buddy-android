# Research: 031-headset-route
<!-- Researcher writes here. Sources on every claim. -->

## 2026-11-20 - diagnosis from live-ls2.log

## Summary
- **Live half of the brief's read: confirmed, plus a second fault.** `grace()` (`AudioRoute.java:229-232`) returns 0 ms for an unseen headset while Android reports the earpiece. Log: `0.26 7:LS2 applied`, `0.56 slipped from 7:LS2 to 1:SM-G990W` (one 300 ms poll, `POLL_MS` `:34`), so `refuseSlip` (`:235`) puts 7 in `liveRefused` and speaker 2 is applied (`:376`, `:396`). 030 itself made this: pre-030 `slipped()` always used `CONFIRM_MS` 2 s (`git show 83ffe8b^:.../AudioRoute.java`, `slipped(applied, actual, sinceMs)`).
- **The SCO does connect, just later than 2 s.** `2.99 slipped from 2:SM-G990W to 7:LS2 Freecom`: Android/Chromium made 7 the communication device ~2.7 s after the first apply (page mic opened 2.71). Buddy then treated that as the *speaker* slipping: `refuseSlip` is false for the first speaker slip (`:235`), so `speakerSlipped=true` and the speaker is applied again over the working SCO (`2.99 2:SM-G990W applied`). Fix must not fight a refused-but-now-reported saved/headset device.
- **Same loop all call long**: 7.59 applied -> 8.44 slipped (earpiece reported again, 0 ms grace) -> speaker; every `watch: setting ...` line is Nicholas's UI changes, each ending in the same refuse. Mic peak 0.000 throughout (comm device was the speaker, not the SCO).
- **Refusals reset more than brief says**: 7 is retried at 7.59, 17.44, 20.00 etc. because `use()` clears `liveRefused` (`:280`, called at `CodexLive.java:771`) and `liveAttempt` drops refusals for devices not listed (`:211`).
- **Dictation: 030 did not touch it.** Out of `AudioRoute`/`LiveWatch`/`SettingsTool`/tests/README, 83ffe8b changed nothing (`git show 83ffe8b --stat`). Non-live path is `reconcile()` after `liveRule()` fails (`:326`): `want()` (`:292`) -> `drop()` when Auto, else `setCommunicationDevice` of the saved key (`:339-357`). The only 030 edits on that path: `use()` calls `LiveWatch.noEarpiece()` (no arg, false with no call) and `afterLive()` also resets `speakerSlipped`. Behaviour identical to pre-030 when no live call is on.
- **Dictation + Auto never uses a call-only SCO headset, by 016 design.** Auto = `want()==""` -> `drop()` = `clearCommunicationDevice` + Android's normal-mode routing (`:331-333`, `:428-434`). `headset()` excludes BLUETOOTH_SCO (`:60-74`); 016 decisions: "A call-only (HFP/SCO) link gets no media in normal mode... A call-only headset can still be picked (001)" (`docs/features/016*/decisions.md:69-72`); Auto's headset step is Android's own routing, "voice input stays on Android's default mic" (`:14`). So with Auto, the mic cannot come from the LS2 SCO and never could. Nicholas switched `audio_device` to Auto earlier today (brief), which explains dictation failing *today* without a 030 code cause.
- **Dictation with the saved pick `7:LS2 Freecom`** goes through unchanged code (`:337-357`): `setCommunicationDevice` is called once and `applied` is trusted; no confirm/retry. Not in the log (log is live only), so whether it works after 030 is `(unverified)`; the likely 030 link is state left by a live call (`applied`/`liveRefused`), reset in `afterLive` (`:418-425`) only when `sawLive` and mode returns to NORMAL.
- Build/test: `AudioRouteTest.java` pure-function tests for `grace`, `liveDevice`, `liveAttempt`, `slipped` (030); `./gradlew testGithubDebugUnitTest` style per README (`(unverified)` exact task).

## Repo map
- `app/src/main/java/app/buddy/assistant/AudioRoute.java`: `reconcile` `:320`, `live` `:366`, `grace` `:229`, `slipped` `:220`, `refuseSlip` `:235`, `liveDevice` `:179`, `liveAttempt` `:207`, `use` `:264`, `afterLive` `:418`, `drop` `:428`, `headset` `:61`, key format `type:name` `:164-170` (7=SCO, 1=earpiece, 2=speaker; stable across reconnects, not indices).
- Callers of `AudioRoute.use`: `Voice.java:115,323,382,523`, `VoiceBar.java:84`, `CodexLive.java:224,771`.
- `LiveWatch.noEarpiece()` now = call is on (`LiveWatch.java`, 030 diff); `liveRule` `:315`.
- Tests: `app/src/test/java/app/buddy/assistant/AudioRouteTest.java`, `LiveWatchTest.java`.

## Prior art
- 016 decision file above (Auto = Android routing; SCO not a headset). 030 `grace()` javadoc claims "headset not seen yet gets none when Android reports the earpiece" (`:224-228`); the log disproves the assumption that a SCO apply leaves the earpiece reported only briefly (it took ~2.7 s here). Android docs cite up to 30 s for a Bluetooth call device (`SETUP_MS` comment `:34`).

## Constraints and risks
- Auto cannot use a call-only SCO without a design change to 016 (apply the SCO as communication device in normal mode, i.e. call mode for dictation). That is the only way "Auto: connected BT headset > watch > speaker" gives headset mic input; the headset must be detected as a call-capable SCO (comm list has `7:LS2 Freecom`, no media output of the same name), and the watch's SCO must not be mistaken for it (016 decisions `:69-72`).
- Earpiece guarantee (030): while the SCO is pending, Android reports the earpiece and the reply may play there for a moment; a pending-SCO wait must not become unbounded for a SCO that never shows (done-when: a failing headset still falls to speaker).
- Re-applying the speaker over a SCO that has just become the device (`2.99`) must be avoided.
- `(unverified)` on-device behaviour of dictation with saved 7; needs a log or a test on the phone.

## Answers to open questions
None asked.
