# Research: 016-audio-auto-priority
<!-- Researcher (claude-sonnet-5-5), 2026-11-16. -->

## Summary
- **Auto today = "leave it to Android" and never picks Watch.** Key `""` → `reconcile()` calls `drop()` (clears the
  communication device, mode back) so Android routes (`AudioRoute.java:121-125`). `want()` maps the Watch key to `""`
  (`:104-107`). Watch is used only if the saved pref is literally `"watch"` (`WatchVoice.chosen`, `WatchVoice.java:53-55`;
  `Voice.speak`, `Voice.java:232-238`). So Auto → watch fallback is **new logic**, in `Voice.speak` plus `choices`.
- **The stale-device symptom is in the saved pref, not in routing.** A picked device is stored as `type:productName` in
  one string pref `audio_device` (`Prefs.java:778-785`, `AudioRoute.java:54-56`). When the device is gone `reconcile()`
  already drops to Android's choice (`:132-135`), but the pref stays; Setup shows `setup_audio_device_missing` ("isn't
  connected: Auto until it is") only on the Setup voice page (`SetupActivity.java:625-627`, `strings.xml:1351`) and the
  voice bar label falls back to index 0 = "Auto" (`VoiceBar.java:166-167`, `Math.max(indexOf,0)`). Exact cause of what
  Nicholas saw (a paired-but-disconnected BT device still listed by `getAvailableCommunicationDevices`, vs. only the
  saved pref shown) is **not reproduced** (unverified); needs a device check.
- **Android has the primitives**: `getAvailableCommunicationDevices()` (API 31) "list of devices that can be selected
  for communication"; `setCommunicationDevice` selection "is active ... until clearCommunicationDevice() is called or
  until the device is disconnected" (https://developer.android.com/reference/android/media/AudioManager, fetched
  2026-11-16). `OnCommunicationDeviceChangedListener` (API 31) and `AudioDeviceCallback.onAudioDevicesAdded/Removed`
  exist for change events. Buddy only uses the callback in Setup (`SetupActivity.java:46-58,101,117`), nothing in
  AudioRoute listens.
- **How phones order outputs (prior art; AOSP/Samsung detail unverified, only the API doc above is fetched):** Android
  routing is "wired headset/USB > Bluetooth (A2DP/SCO/LE Audio, most recently connected wins) > built-in earpiece/speaker";
  for calls, earpiece vs. speaker is the last resort. Samsung and iOS behave alike (last-connected headset wins). The
  brief already states this model; a fixed rank with ties broken by most recently connected is the standard. No
  user-editable priority list is standard on stock Android (non-goal stands).
- **Watch is not an Android device** (001 verify; `AudioRoute.java:19`, `docs/features/012-watch-audio-device/research.md`
  Summary bullet 1), reachability comes from `WatchVoice.Link.find` (capability `buddy_watch_read`, `FILTER_REACHABLE`,
  3 s timeout, `WatchVoiceLink.java:~14,42-53`). It is async (callback), so "at the moment of speaking" must wait on it or
  use a cached last answer. F-Droid / no-link builds: `WatchVoice.link()==null` → no Watch anywhere (`AudioRoute.java:43`).
- **A Watch fallback already exists once chosen**: `ReadQueue` finds a node, plays on watch, else phone reads after a
  spoken notice once per stretch of fallbacks (`ReadQueue.java:9-10,149,181-186`, `voice_watch_unavailable`). Auto
  falling to Watch should likely reuse it quietly (no "your watch isn't available" notice when Watch was never chosen).
- **Interplay Watch vs headset**: with buds connected and "Watch" chosen, read aloud goes to the watch only; headset
  routing is not applied (`want()`→`""`, Android's own choice though still plays on phone-side only if watch fails).
- **"Setting shows what Auto is using now"**: no such API in code. Surfaces to change: Setup note
  (`SetupActivity.java:626-633`), segmented label for Auto (`choices` `audio_auto`, `strings.xml:1355`), voice bar
  label (`VoiceBar.java:166-170`), `buddy_settings` read (`SettingsTool.java:342-351`). Current device for Android
  routing is readable with `AudioManager.getCommunicationDevice()` (API 31; only meaningful while a mode/selection is
  active, unverified) or by ranking `getAvailableCommunicationDevices()`.
- **Below API 31 there are no device lists**: `choices` offers only Auto (+Watch), `use()` returns at once
  (`AudioRoute.java:38,79`). Ranking only applies to 31+; Auto→Watch fallback could apply on any version.

## Repo map
- Choices/keys/labels: `AudioRoute.java:35-71`; apply/poll: `:78-196`; `Prefs.audioDevice`: `Prefs.java:778-785`.
- Callers of `AudioRoute.use`: `Voice.java:106,238,296,437`, `CodexLive.java:215,746`, `VoiceBar.java:84`.
- Watch: `WatchVoice.java` (KEY, Link, chosen, speak), `ReadQueue.java` (testable core), github-only
  `app/src/watchlink/java/app/buddy/assistant/WatchVoiceLink.java`.
- UIs: Setup `SetupActivity.java:613-634` (audio devices callback `:46-58`), `VoiceBar.java:82-84,166-170`, `SettingsTool.java:342-351`.
- Types seen in `label()`: speaker, earpiece, wired headset/headphones, else product name (`AudioRoute.java:58-71`).
  Rank would key on `AudioDeviceInfo.getType()` (BLE_HEADSET, BLUETOOTH_SCO, WIRED_*, USB_HEADSET, HEARING_AID, BUILTIN_*)
  via a pure `int rank(type)` function (JVM-testable without Android classes if types are ints).
- Tests: `app/src/test/java/app/buddy/assistant/` has `ReadQueueTest` (fake `Out`, good template), `SettingsCoverageTest`
  (allowed values come from `choices`, `:41`). No AudioRoute test exists (it uses Android classes; extract the order).
- Build/test: `./gradlew testGithubDebugUnitTest` style per flavor (`app/build.gradle.kts:91` junit 4.13.2); CI
  `.github/workflows/build.yml` (unverified exact task name; check before the plan).

## Constraints and risks
- Main source cannot see the watch link (reflection only, `WatchVoice.java:5-8,40-47`); Auto→Watch must go through `link()`.
- `Link.find` takes up to 3 s: a synchronous order check at speak time is impossible; options are cached state from the
  last find or wait (ReadQueue already waits).
- Android only reports connected devices to apps; BT "paired but off" should be absent from the list, but a profile
  switch (A2DP vs SCO/LE) can change type/name key between listings (key = `type:productName`, `AudioRoute.java:53-56`),
  so a picked-device match may flap (unverified).
- Fallback must not cause silence: if the picked device is away and Auto's choice (watch) also fails, ReadQueue falls to
  the phone (existing). Live voice (`CodexLive`) is phone-only; Watch never applies there.
- Voice uses call mode when a device is applied (`AudioRoute.java:138-142`): auto-picking a headset should not force
  call mode just to prefer it unless needed; today Auto applies nothing and lets Android route A2DP media normally.
  Applying `setCommunicationDevice` for Auto would change that (risk: media/A2DP → SCO quality drop).
- `SettingsCoverageTest` constrains `audio_device` allowed values to `choices`; changing Auto's label/value set must keep it green.

## Open points for the Planner
- Is Auto's "headset first" real routing (Android already does it with Auto → nothing to build) or only the Auto→Watch→speaker
  fallback plus "shows what Auto uses"? Android's own pick probably already prefers connected BT/wired over speaker.
- Where the stale device came from (pref vs. listing); cheapest guard is resolving a missing picked device to Auto at
  display and speak time (already does at routing time).
