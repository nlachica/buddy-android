# Plan review: 017-live-voice-watch
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] The headset fallback still permits the earpiece - `plan.md:42-56` returns `NONE`/`""` whenever a media headset is connected, without establishing that it carries communication audio. That is exactly the `want().isEmpty()` → `drop()` → `clearCommunicationDevice()` path in `app/src/main/java/app/buddy/assistant/AudioRoute.java:209-211,248-251`. The plan itself acknowledges that Bluetooth may not carry the call without SCO (`plan.md:242-244`), and rejects guaranteeing no earpiece in this case (`plan.md:194-195`). Feature 016's rationale explicitly depended on normal-mode media routing (`docs/features/016-audio-auto-priority/decisions.md`, “A headset means a media output”); it does not establish the same result for this WebView call. Thus Watch selected + unavailable watch + connected A2DP headset can still take the diagnosed earpiece path, contrary to `brief.md:13,20`. -> Define how a usable headset communication route is selected/confirmed, with loudspeaker fallback when it cannot be used; do not equate media-device presence with a successful call route. Add a device check with buds and the watch's SCO both present, watch streaming unavailable, and evidence of the actual communication output. Android's [communication-device API](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) supports explicit selection from available communication devices and reports rejection.

F2 [major] Auto's watch decision uses the routing query the plan says is unreliable during calls - `Out.watchWins()` delegates to `AudioRoute.target()` (`plan.md:81,98`), which uses `headsetNow()` and its API 33 routed-output answer (`app/src/main/java/app/buddy/assistant/AudioRoute.java:93-115`). A nonempty speaker/earpiece answer suppresses connected-headset detection (`:76-82`; the speaker-plus-connected-A2DP case is explicitly asserted in `app/src/test/java/app/buddy/assistant/AudioRouteTest.java:29`). The plan already recognizes this communication-mode problem at `plan.md:47-48`, but only fixes the separate phone-route calculation. For example, switch a running Speaker call to Auto with buds connected: a speaker answer makes `target()` choose Watch, and the 500 ms loop never reconsiders once the setting is unchanged. This contradicts Auto's headset-before-watch requirement even without adding headset hotplug support. -> Give the live forwarding decision the same communication-mode-safe headset observation as the live route rule, leaving 016's normal-mode media query intact. Test Speaker → Auto and Watch → Auto with a connected headset and a routed speaker answer; assert no watch forwarding. A fake `watchWins=true/false` alone does not exercise this defect.

F3 [major] Microphone unmuting is timed from production, not watch playback - `plan.md:158-164` reenables the phone microphone one second after the page last detects voice, but the design explicitly allows roughly 2–3 seconds of delayed watch playback (`plan.md:232-234`). In that allowed state the microphone reopens while the watch is still speaking, recreating the self-interruption/false-user-transcript problem the half-duplex rule is meant to prevent. This differs materially from 007: `wear/src/main/java/app/buddy/assistant/wear/Call.java:245-247` gates on `Speaker.audibleNow()`, whose deadline includes actual queued playback (`Speaker.java:136-139`). The silence gate only stops adding samples; it does not discard audio already in the transport or the blocking reading player (`Speaker.java:164-165`), so a pause longer than one second does not necessarily drain a larger backlog. There is also a threshold mismatch: the proposed gate forwards peaks above 0.01 (`plan.md:119`), while the live page's `voice` flag uses 0.02 (`app/src/main/java/app/buddy/assistant/CodexLive.java:958`). -> Specify a playback-aware mute deadline or a demonstrated end-to-end delay bound with a matching mute tail and failure policy; align the audibility thresholds. Reconsider the self-imposed “no watch change” constraint if needed. Add checks with delayed playback exceeding one second and quiet audio between the two thresholds; the microphone must remain suppressed while that audio is audible. The normal-link D2 smoke test does not cover this known failure.

F4 [major] API 30 exposes Watch without the promised fallback fix - The app supports API 30 (`app/build.gradle.kts:22`), and Watch is offered independently of the API 31 communication-device choices (`app/src/main/java/app/buddy/assistant/AudioRoute.java:43-48`). Nevertheless, the entire new routing behavior remains behind `use()`'s API 31 guard (`:165-166`); `plan.md:245-246` explicitly leaves API 30 to Android's routing while enabling watch streaming there. Consequently an unavailable or dropped watch on a supported phone has no planned loudspeaker guarantee, and no test covers it. `brief.md:20` contains no platform exception. -> Include an API 30 fallback and teardown strategy with validation, or obtain an explicit supported-platform scope decision and make the exposed behavior and acceptance criteria consistent with it. Recording the unmet done-when item only as a risk is insufficient.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - in a phone call, a media headset being connected doesn't mean Android routes the call to it. With no
communication device set, Android plays the call on a wired or USB headset by itself, but never on A2DP, and Bluetooth
only gets the call over SCO, which Buddy has to select. So `liveDevice` now picks a headset's own communication device
explicitly: the first wired, USB, BLE or hearing-aid communication device, or a SCO device whose name is that of a
connected media headset (the buds' call link). The watch has no media output, so its SCO never matches. Otherwise it
picks the built-in speaker, and never the earpiece. Picking the device isn't the same as using it, so two cases fall
back to the speaker:
- `setCommunicationDevice` refuses the device: reconcile runs again in the same tick, and the refused key is skipped.
- Android's `getCommunicationDevice()` isn't the applied key 2 s after applying (the headset's call link failed): that
  key counts as refused and the speaker is applied. The same check re-applies the speaker if Chromium picks the
  earpiece again later. That was Risk 9, now closed.

Buds without a call profile have no communication device, so they get the speaker. The rule now has unit tests:
buds' SCO plus the watch's SCO → buds; the watch's SCO alone → speaker; refused → speaker; no headset → speaker; never
the earpiece. Device check D8: buds plus the watch, Watch picked, the watch refuses → `dumpsys audio` shows the buds'
SCO, the voice is in the buds, and disconnecting the buds moves it to the speaker. Old Risk 6 is gone. The
"strict never-earpiece with a headset" alternative is now the design.

F2: accepted - the forwarding decision no longer uses `target()`. The new pure `AudioRoute.livePick(saved, savedHere,
connected, watch)` is `pick(saved, savedHere, headsetIn(null, connected) != NONE, watch)`: 016's media-headset types
from `getDevices(OUTPUTS)` only, without the API 33 routed answer. `Out.watchWins()` uses it through
`AudioRoute.liveTarget(app)`. 016's `target()`/`headsetNow()` stay as they are for normal mode. Tests: `livePick` with
outputs `{SPEAKER, A2DP}` gives `""` for Auto (Speaker → Auto and Watch → Auto with buds: no watch), `{SPEAKER, SCO}`
gives watch, and a picked Watch with A2DP still gives watch. `LiveWatchTest` (7) checks that `watchWins=false` after a
change of the setting means no find. D6 adds Speaker → Auto mid-call with buds: no "watch: looking" line in live.log.

F3: accepted - the mic decision moves from the page into Java and follows the watch's playback, not the page's
production:
- The call stream reports each block once it is actually written into the channel (an optional `sent` callback in
  `PcmOut`, passed through `ReadStream`/`openCall`).
- `LiveWatch` keeps a phone-side copy of the watch `Speaker`'s `audibleUntil`: a playhead advanced by each written
  block at real-time rate (`until = max(now, until) + len/32 ms`), with the audible mark set by audible blocks.
- `toWatch()` now returns 0 (the voice is on the phone), 1 (on the watch, mic open) or 2 (on the watch, mic closed).
  The mic is closed while an audible block handed to the stream hasn't been written yet, or until `audibleUntil +
  1 s` (the watch's 300 ms prebuffer, its track and the link, with margin).
- One threshold, 0.01 (`Speaker.AUDIBLE`), now decides both what is forwarded and what keeps the mic closed. The page's
  0.02 `voice` flag only drives the bar.

Failure policy: the call's phone queue is now non-live with a 1-s cap (it replaces the 2-s drop-oldest queue, so
`ReadStream` needs no `live` flag). If the watch falls more than the transport's buffers plus 1 s behind, the stream
breaks, the voice moves to the phone loudspeaker and the mic opens. Audio held inside the Data Layer that never backs
up to the phone is invisible to the phone. That residual is stated in Risk 1, with device check D2b, and its
follow-up would be a watch-side signal (a watch update), which is out of scope.

New unit cases:
- a block between the two thresholds (peak 0.015) is forwarded and gives 2;
- a block written 1.5 s late keeps 2 until its playhead plus the tail;
- a backlog written at once extends it;
- an overflow → fallback → 0;
- `PcmOut`: `sent` comes in order and never for discarded audio, and the call cap overflow → broken.

The no-watch-change constraint stays, because a watch update means `adb install` from a computer over Wi-Fi (README).

F4: accepted (scope) - 001 already decided the platform scope for audio routing: "Android 12+ only; hidden on Android
11 ... the legacy calls are deprecated and would be a second code path ... On API 30, Buddy behaves as today"
(`001-audio-io-selector/decisions.md`). 017's live-call part follows it. `LiveWatch`'s static `begin` returns at once
below API 31, so on API 30 no watch path is exposed without its fallback, `noEarpiece` stays false, and a phone live
call is today's, where Watch is Auto, as 012 shipped. The Watch texts say live voice on the watch needs Android 12+.
The plan's Goal states that done-when (a) and (b) are for Android 12+, and Open questions asks Nicholas to confirm.
This isn't blocking: his phone (S21 FE) is API 31+, where the Verifier checks. A legacy `setSpeakerphoneOn` path was
rejected again for 001's reasons, and it couldn't be checked on any device he has.
