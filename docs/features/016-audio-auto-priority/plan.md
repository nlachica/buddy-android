# Plan: 016-audio-auto-priority
<!-- Planner (claude-opus-5-5), 2026-11-16. Sources: brief.md, research.md, code read at bd595f3, 001/012 decisions and
     verify, Android reference for AudioManager/AudioDeviceInfo (fetched 2026-11-16, facts quoted under Design). -->

## Goal
Buddy's audio device setting (001's selector plus 012's Watch) must never stay stuck on a device that is gone. **Auto**
uses the best output that is connected *when Buddy speaks*, in the order phones use: a connected Bluetooth, wired or
USB headset (Galaxy Buds, LS2 FreeCom; when several are connected, the latest one wins, as Android decides), then the
Galaxy Watch (012 path, github build) when it answers, then the phone speaker. A **picked** device is used while it is
connected. While it is gone Buddy follows the same order, and it uses the picked device again once it reconnects.
Done when: (1) Auto with buds goes to the buds; buds off → watch (when reachable); watch unreachable → speaker; nothing
stale at the moment of speaking. (2) Picked device disconnected → falls back by the order with no silence; reconnected
→ used again. (3) The setting shows what Auto is using now; the F-Droid build is the same minus Watch; CI green; unit
tests for the order.

Non-goals: a user-editable priority list (brief; research: stock Android has none); routing calls or other apps' audio;
moving a reading that is already playing to another device mid-sentence (the order applies from the next reading);
Auto taking a headset's **microphone** (Auto's voice input stays on Android's default, as today; picking the headset
gives its mic, see Risk 4); the voice bar on the watch.

## Design

### What Android already does, and what is left for Buddy
- With nothing applied (Auto today: `AudioRoute.reconcile` → `drop()`), Android plays Buddy's voice (TTS
  `USAGE_ASSISTANT`, ChatGPT's voice in the WebView) by its own media routing. That routing already prefers a connected
  headset over the speaker and takes the most recently connected one when there are several. This is the "most
  recently connected wins" rule from the brief and research, and Buddy can't do it better: apps see no connection
  times. **So Auto's headset step is Android's own routing, unchanged.** Buddy only needs to know *whether* a headset
  is connected, so that it can decide between "leave it to Android" and "try the watch".
- The watch is not an Android audio device (001 verify; 012). Only Buddy can put it in the order, between headset and
  speaker. Only read aloud can go there (012 non-goal: mic and live voice never use the watch).
- A picked device that is gone is already dropped for routing (`reconcile`: not in `getAvailableCommunicationDevices`
  → `drop()`), and re-applied on reconnect (001). Two things are missing. Buddy's voice falls back to Android's choice
  only, so the watch step is skipped. And the UI shows only the saved name.

### The order: one pure function
In `AudioRoute` (main source, no Android types in the signature, JVM-testable):

```java
/** Output types Android plays on before the phone itself: headsets and other personal BT/wired/USB outputs. */
static boolean headset(int type)   // WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET, BLUETOOTH_SCO, BLUETOOTH_A2DP,
                                   // HEARING_AID, BLE_HEADSET, BLE_SPEAKER → true; everything else false

/** Where Buddy's voice goes. Returns the saved key, WatchVoice.KEY, or "" (Android's own routing). */
static String pick(String saved, boolean savedHere, boolean headset, boolean watch) {
    if (!saved.isEmpty() && savedHere) return saved;   // a pick in effect (Watch: when this build has the link)
    if (headset || !watch) return "";                  // a headset (Android picks the latest), or no watch link
    return WatchVoice.KEY;                             // the watch; the phone (speaker) when none answers
}
```

`TYPE_*` are compile-time constants (inlined, so the test loads no `AudioDeviceInfo`). `TYPE_BLE_*` are API 31 and
simply never match on API 30.

Android side, also in `AudioRoute`:
- `headsetNow(Context)` → the connected headset, or null. On API 33+, first look at
  `getAudioDevicesForAttributes(USAGE_ASSISTANT / CONTENT_TYPE_SPEECH)`, Android's own pick ("Get the audio devices that
  would be used for the routing of the given audio attributes", API 33, public, no permission). That names the right
  headset when several are connected. Otherwise, and as the fallback, use the first `headset()` in
  `getDevices(GET_DEVICES_OUTPUTS)` ("the audio devices currently connected", API 23, so it also works on API 30, the
  minSdk).
- `target(Context)` = `pick(saved, indexOf(choices(c), saved) >= 0, headsetNow(c) != null, WatchVoice.link() != null)`.
  `choices()` already lists exactly the connected communication devices plus Watch when the link exists, so
  `savedHere` needs no new code. Computed fresh on every call: no cache, so nothing can be stale at the moment of
  speaking. It's a few binder calls on the main thread, the same kind `choices()` makes today.
- `androidNow(Context)` → the label of what Android's routing uses now: `label(c, headsetNow)` (the existing label:
  product name, or "Headset" for wired) or "Speaker".

Routing (`use`/`want`/`reconcile`) **does not change**: a pick in effect is applied as in 001, everything else drops to
Android's routing.

### Speaking: the watch step goes through ReadQueue, quietly
`Voice.speak` (`Voice.java:234-241`) today sends the text to the watch only when Watch is saved. New:

```java
String t = AudioRoute.target(c);
if (WatchVoice.KEY.equals(t)) WatchVoice.speak(c, clean, !WatchVoice.KEY.equals(Prefs.audioDevice(c))); // quiet = Auto's step
else here(c, clean);
```

The watch step needs a fresh answer to "is a watch reachable?", which is async (`Link.find`, up to 3 s). `ReadQueue`
already asks once per reading, keeps readings in order (one in flight) and falls back to the phone. That makes it the
"at the moment of speaking" check. A reading in Auto's watch step is a **quiet** item:
- watch found → played on the watch exactly as in 012;
- no watch, or the watch refused → **no notice** (the user didn't pick Watch, so "your watch isn't available" would be
  noise) and the text goes to `Out.here(text)` = Voice's normal phone path (`Voice.here`: ChatGPT's voice when chosen,
  the phone's as its fallback). That is what Auto users without a watch hear today. The item ends at once; the phone
  path keeps its own order (CodexSpeech queue, TTS `QUEUE_ADD`). It isn't waited for, because the ChatGPT voice on the
  phone has no "done" (`CodexSpeech.speak` has only a fallback);
- quiet fallbacks leave `noticeDue` alone, so the 012 notice still comes the first time a *picked* Watch can't play.

With Watch picked, nothing changes (012: notice, then the phone TTS). Its phone fallback plays through Android's
routing, so it already follows the order: a connected headset, else the speaker.

### What the setting shows
One helper, `AudioRoute.autoNow(Context, Consumer<String>)`: the label of what Auto uses right now. It runs `pick("",
false, headsetNow != null, link != null)`: "" → `androidNow` at once; Watch → ask the link, then "Watch" or "Speaker".
It is used only when Auto is in effect (saved empty, or `target ≠ saved`). The watch answer is reused for 5 s (display
only; a reading always asks itself), so the voice bar's once-a-second refresh doesn't run a capability lookup every
second.
- **Settings → Voice → Audio device** (`SetupActivity.java:613-634`): under the segments, Auto or an away pick gets
  "Auto is using <X> now." after the existing text (the rule, or "<name> isn't connected: Auto until it is."). The text
  is set again when the watch answer lands, if the note is still attached. A picked Watch keeps 012's note and its
  unreachable variant. Re-render on headset changes already happens (`AudioDeviceCallback`, `SetupActivity.java:46-58`).
  The rule text says the order: github "…Auto uses a connected headset (the latest one), else your watch for read
  aloud, else the phone"; F-Droid (no link) the same without the watch.
- **Voice bar chip** (`VoiceBar.showDevice`, `:164-171`): "Auto: <X>" when Auto is in effect, the pick's label
  otherwise. Same 1-s refresh (`ticks % 14`). Max width 88 → 120 dp so "Auto: Galaxy Buds2" mostly fits (the wave
  shrinks; ellipsis stays). Tap-to-cycle unchanged.
- **`buddy_settings` read** (`SettingsTool.java:342-356`): `read()` adds a line `now: <X>`: the pick's label; or `auto
  → <androidNow>`; or `auto → watch, or <androidNow> when no watch answers`; plus `(<saved> isn't connected)` for an away
  pick. It's synchronous, so it states the watch step rather than asking. `get()` is unchanged (it's the snapshot the
  approval card compares). `allowed()` gains one clause describing Auto's order.

### Alternatives considered (one line each)
- Auto applies the top-ranked headset as the communication device (mic + speaker over SCO): needs connection order
  Buddy can't see, forces call mode and SCO quality on read aloud, changes 001's "Auto behaves as before". Rejected.
- Rank by `getAvailableCommunicationDevices()` only: misses A2DP-only outputs (car, BT speaker) where Android plays
  anyway. Rejected for the headset check; still used for `savedHere`.
- A cached watch reachability (listener or periodic) checked synchronously in `Voice.speak`: can be stale at speak
  time, against done-when 1. Rejected for routing; a 5-s memo is used for display only.
- Deciding watch-or-phone in `Voice` before queueing: async finds would reorder replies. Rejected; ReadQueue already
  serializes.
- Clearing the saved pick when its device disconnects: breaks "reconnects → used again". Rejected.
- Quiet fallback through phone TTS with `done` (waitable): would swap ChatGPT's voice for the phone's for Auto users
  with no watch around, a regression. Rejected.

## Steps
1. **The order, pure, with tests.** `AudioRoute.java`: add `headset(int)` and `pick(...)` as above, and update the class
   comment (Auto = the order, not "Android decides"). New `app/src/test/java/app/buddy/assistant/AudioRouteTest.java`
   (see Test plan). If loading `AudioRoute` in the JVM trips on its static `Handler`, make it lazy like `Voice.post`
   (`isReturnDefaultValues = true` should make that unnecessary).
2. **Android side of the order.** `AudioRoute.java`: `headsetNow` (private), `target`, `androidNow`, `autoNow` (with the
   5-s watch memo), each with a one-line comment pointing at this plan. No change to `use`/`want`/`reconcile`.
3. **Speaking in the order.** `ReadQueue.java`: `speak(text, chatgpt)` delegates to new `speak(text, chatgpt, quiet)`;
   `Item.quiet`; `Out.here(String text)`; `phone(r)` starts with `if (r.item.quiet) { out.here(r.item.text); end(r);
   return; }`; class comment mentions Auto's watch step. `WatchVoice.java`: `speak(c, clean, quiet)`; `Phone.here` =
   `SpeechPause.begin(app); Voice.here(app, text)`; remove `chosen()` (its only caller is `Voice.speak`). `Voice.java`:
   `here` becomes package-private; `speak` routes by `AudioRoute.target` as above. `ReadQueueTest.java`: the Fake gets
   `here` (logs `"here " + text`), plus the new tests. Existing tests unchanged.
4. **Showing it.** `SetupActivity.java` (audio device note), `VoiceBar.java` (`showDevice`, chip max width),
   `res/values/strings.xml`: new `audio_auto_now` ("Auto: %1$s"), `setup_audio_device_now` ("Auto is using %1$s now."),
   `setup_audio_device_info_watch`; reword `setup_audio_device_info` (rule without watch). English only, as all audio
   strings are today (no `values-*` copies exist).
5. **Settings tool and docs.** `SettingsTool.java`: `read()` override and the `allowed()` clause for `audio_device`.
   `README.md:100` area: one bullet "Audio device Auto" (the order, a picked device falls back and comes back, Settings
   shows what Auto is using).

## Test plan
| Done-when | Proof |
|---|---|
| 1. Auto: buds → buds; off → watch; unreachable → speaker; not stale | **Unit** `AudioRouteTest`: `pick("", false, true, true)` = "" (headset wins over watch); `pick("", false, false, true)` = "watch"; `pick("", false, false, false)` = "" (speaker / F-Droid); `headset()` true for the 8 types, false for BUILTIN_SPEAKER, BUILTIN_EARPIECE, BUILTIN_SPEAKER_SAFE, TELEPHONY, REMOTE_SUBMIX. **Unit** `ReadQueueTest`: quiet + no node → `[find, here a]`, no NOTICE, next text starts its own find; quiet + refused → stopProducers, `here`; quiet + node → plays on the watch as non-quiet; a quiet fallback then a non-quiet no-node → NOTICE still said. "Not stale": `target` has no cache and ReadQueue asks per reading (code review). **Device** (Verifier with Nicholas, github build): Auto, Buds2 on → a reply heard in the buds; buds in case → next reply on the watch, no notice; watch Bluetooth off → next reply on the speaker, no notice, delay to speech noted (Risk 1). |
| 2. Picked gone → order, no silence; back → used | **Unit**: `pick("7:LS2 FreeCom", false, true, true)` = ""; `pick("7:LS2 FreeCom", false, false, true)` = "watch"; `pick("7:LS2 FreeCom", true, true, true)` = the key (a pick beats a headset); `pick("watch", true, true, true)` = "watch"; `pick("watch", false, false, false)` = "" (stale "watch" in a build without the link). **Device**: pick the FreeCom (or Buds2) in Settings; turn it off → reply on the other headset / watch / speaker by the order, Settings says "… isn't connected: Auto until it is. Auto is using <X> now."; turn it on → next reply on it, its segment highlighted. While it's off, `buddy_settings` read's allowed list must not include it (Risk 2). |
| 3. Shows what Auto uses; F-Droid minus Watch; CI; order tests | **Device**: Settings note and voice bar chip show "Auto is using <X> now" / "Auto: <X>" for each step above; `buddy_settings` read shows `now: …`. **F-Droid**: no link → `pick(…, watch=false)` never returns "watch" (unit) and the rule text without the watch is used (code review); `assembleFdroidRelease` in CI compiles it. **CI**: `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease` (`build.yml:63`) green. No Android SDK on the phone: CI is the build and test run. |

001/012 regressions to re-check on device: a picked Speaker/Phone still applies (call mode) and cleans up after;
Watch picked still plays on the watch and gives the notice when it can't; live voice unchanged.

## Risks
1. **Delay in Auto's watch step with no watch around.** Each reading waits for `find` before the phone speaks. With the
   watch disconnected the capability lookup should answer from Play services' local node list in well under a second,
   but the guard is 3 s. Verifier notes the delay; if it's noticeable, the follow-up is a quick "any connected node?"
   pre-check, not part of this plan.
2. **Why the FreeCom looked stuck is not reproduced** (research). If Android keeps listing a device that is off in
   `getAvailableCommunicationDevices()`, Buddy and Android both treat it as connected and route there. The Verifier
   checks this with the FreeCom off. If it is still listed, a follow-up guard (drop a pick whose communication device
   never comes up) needs on-device evidence first.
3. **What counts as a headset.** BT A2DP covers cars and BT speakers. Android plays there anyway, so Auto follows, as
   phones do. USB DACs (`TYPE_USB_DEVICE`), HDMI and docks are not in the list: there Auto may pick the watch while
   Android would use that output. Rare; accepted.
4. **Auto doesn't take the headset's mic.** Output follows the headset; voice input under Auto stays on Android's
   default, as today (live voice: Chromium picks the BT headset itself). On a helmet (FreeCom), Nicholas can pick the
   FreeCom to get its mic. If he wants Auto to do that too, it's a follow-up with call-mode cost (SCO quality on read
   aloud). Not blocking: the done-when is about output.
5. **Overlap edge.** A quiet fallback isn't waited for. If a watch becomes reachable while the phone is still reading,
   the next reading can start on the watch at the same time. Needs the watch to appear between two readings; accepted.
6. **Mid-reading changes.** A picked device that leaves mid-reading: the 300-ms poll drops it, and Android's routing
   finishes the reading on a headset or the speaker (no silence). The watch step only applies from the next reading.
7. **Label edge in live voice.** On API 33+ during Chromium's call, `getAudioDevicesForAttributes` may report the
   call's device; the `getDevices` fallback still finds a connected headset, so the label stays right. Display only.
8. **Profile flapping** (research): a headset whose communication type changes (SCO ↔ LE) changes its key, so a pick
   can look away for a moment. Auto then plays on the same headset through Android anyway (output), and the pick
   returns on the next match.

## Open questions
None blocking. (Risk 4, Auto also taking the headset's mic, is for Nicholas after he tries it; the plan doesn't need
the answer.)
