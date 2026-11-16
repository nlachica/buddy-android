# Code review: 016-audio-auto-priority
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

F1 [major] Disconnect between device snapshots breaks settings reads - `app/src/main/java/app/buddy/assistant/SettingsTool.java:360-363`: `AudioRoute.target()` checks its own `choices()` snapshot, then `read()` obtains another snapshot. If the picked FreeCom disconnects between those binder queries, `t` still equals `saved`, but the second list has no matching device. The selected branch indexes `devices[-1]` even though `away` is already true. This throws `ArrayIndexOutOfBoundsException` instead of reporting the fallback, affecting both an `audio_device` read and the full settings listing. Main-thread execution does not prevent Android's device list changing between queries. Reproduced in an isolated Java probe using the actual unchanged `read()` override with a connected target followed by a disconnected device list. -> Derive the selected-device label from one local index and only access it when nonnegative; otherwise describe the Auto fallback from that snapshot. Add a regression check for the selected device disappearing between queries.

F2 [minor] Every voice-page redraw adds another repeating refresh - `app/src/main/java/app/buddy/assistant/SetupActivity.java:129-137,230-232,652-658`: render removes the old note and posts a new `autoTick`, but never cancels the old note's pending tick. When the old tick runs, it reads the mutable `autoNote`, sees the new attached view, and schedules itself again there. After one redraw there are two permanent refresh chains, with another added by each device callback or settings redraw until cleanup. The detached-view check therefore does not provide the intended cleanup; redundant routing and Wear capability queries grow with redraws. -> Remove the old tick before detaching/replacing the note, clear its reference and invalidate outstanding answers, then start exactly one refresh for the new note. Check repeated redraws leave only one pending refresh and pause removes it.

F3 [minor] Connected explicit picks incorrectly display Auto as active - `app/src/main/java/app/buddy/assistant/SetupActivity.java:645-658`: the Auto note and timer are enabled for every selection except Watch. With Speaker explicitly selected and a reachable watch, Settings can say "Auto is using Watch now" while readings correctly use the selected speaker. A connected headset pick has the same mismatch. The plan's "What the setting shows" section explicitly restricts this status to an empty saved pick or an unavailable pick. -> Start `autoNow`/the refresh only when `saved.isEmpty() || away`; keep the ordinary description for a connected explicit device and the existing Watch-specific note. Check connected Speaker, Auto and a disconnected saved headset separately.

Coverage and validation: the priority, media-type filtering, fresh queue-start/post-find routing, phone-busy waits, quiet fallbacks and picked-Watch compatibility have the planned unit coverage. No additional security or unnecessary-scope findings. The API-33 query matches the [Android AudioManager reference](https://developer.android.com/reference/android/media/AudioManager#getAudioDevicesForAttributes(android.media.AudioAttributes)). Reviewed against `main...HEAD` at `4db951b`.

- Compiled current main/github/watchlink sources and ran all 145 JVM tests successfully using the existing temporary harness, with output isolated in `/tmp/buddy-review-016`.
- Compiled current main/fdroid sources and ran all 29 AudioRoute/ReadQueue tests successfully with that harness.
- This is limited compilation evidence: the harness uses an API-35 jar, generated resources, Wearable stubs and Android runtime shims, and removes an unrelated API-36 call from its temporary source copy. It does not establish a real Android/Gradle build or CI success.
- `./gradlew --no-daemon testGithubDebugUnitTest --offline` could not start: the host JVM reported "Failed to mark memory page as executable". No APK, device routing, Settings lifecycle or Bluetooth/Watch smoke checks were run. Those remain with the Verifier, including the documented FreeCom availability uncertainty and ChatGPT fallback/startup risks.

Application code was not modified.

VERDICT: REVISE

### Coder response (round 1)
- F1: fixed in 684c497. The read's wording moved into `SettingsTool.audioNow(saved, target, devices, android)`, which takes one device list and only indexes it when the pick is in that list; a pick that vanished reads "auto → <android> (X isn't connected)". Regression test: `AudioRouteTest.settingsReadSurvivesAPickThatVanishedBetweenQueries`.
- F2: fixed in 684c497. `render` removes the old note's `autoTick` and bumps `autoSeq` before the new note is set, so one chain exists per render. Not unit-testable (view lifecycle); the `onPause` removal is unchanged.
- F3: fixed in 684c497. The note's refresh starts only when `saved.isEmpty() || away`; a connected explicit pick (Speaker, headset) keeps the ordinary description, and Watch keeps its own note and find.
- Run: Gradle can't start on this host (same JVM "Failed to mark memory page as executable" the reviewer hit), so the new test and the build were NOT run. Left to the Verifier / CI.
