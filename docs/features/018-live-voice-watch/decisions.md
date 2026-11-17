# Decisions: 018-live-voice-watch
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-17 - The call's voice reaches the watch through 012's reading receiver; the mic stays the phone's
- Options: 007's full Remote for phone-started calls (watch mic and speaker; needs a phone→watch call handshake and a
  watch update); a new watch receiver for call audio (live Speaker, longer wake lock; watch update); 012's
  `/buddy/read/<id>` channel and `ReadAloud`, as installed, with an open-ended stream.
- Choice: 012's receiver, because the installed watch app (012+) already plays any stream on that path, so nothing
  needs an adb reinstall. The brief keeps the mic unless 007's path fits simply, and it doesn't (Remote is
  all-or-nothing and watch-initiated). Costs (plan Risks 2-4): the reading player never drops, its wake lock is
  11 min, and the watch shows the reading notification.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - During a phone live call whose voice belongs to the watch, the phone route is a headset (Android's routing) else the built-in speaker
- Options: diagnosis' "watch → speaker key" alone; a headset first via Android's routing (016), else the speaker;
  choosing the headset's communication device ourselves (can't tell buds' SCO from the watch's SCO).
- Choice: pure `AudioRoute.liveDevice(comm, connected)`: 016's media-headset rule on the connected outputs → `""`,
  else the first `TYPE_BUILTIN_SPEAKER`, never the earpiece. It applies when Watch is picked, and under Auto only once
  the watch took the voice in this call (the brief's "falls back to the phone loudspeaker if the watch drops"). Auto
  without the watch stays `""` as today. It uses `getDevices(OUTPUTS)`, not `headsetNow()`, because in communication
  mode Android's routed answer follows the speaker Buddy set and would hide a headset. While the voice is on the
  watch, the route stays the same (the phone mic in speakerphone tuning suits a phone held away), so a drop needs no
  route change.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - LiveWatch decides at call start and on a change of the setting; mutes the phone on attach; no retry otherwise
- Options: decide once at start; also on setting changes; also on headset changes; retry after a drop. Mute when the
  stream opens, or when its channel is attached.
- Choice: start plus setting changes, found by a 500 ms poll of the saved value (no hook in Setup, the voice bar or
  SettingsTool, which doesn't call `use()`). Headset changes would need `target()` in communication mode, where it is
  skewed, and the brief doesn't ask for it. A refusal or drop falls back for the rest of the call unless the setting
  changes, so the voice doesn't flap. It mutes on attach, so a 10-s open timeout never silences the phone.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The page pulls `Buddy.toWatch()` in its 70 ms timer; the tap is added lazily
- Options: Java pushes `evaluateJavascript("route(…)")`; the page polls a bridge method in its existing timer.
- Choice: poll, because a push before the page has loaded is lost silently and the timer is already there. The tap
  is added only when the voice first goes to the watch, so phone-only calls keep today's audio graph.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Bounded delay: a live 2-s phone queue and a phone-side silence gate
- Options: reuse the reading stream as is (10-min queue, never drops: a stall becomes permanent delay); change the
  watch `Speaker` to live for calls (watch update); keep it on the phone side: a live `PcmOut` (007's 2 s,
  drop-oldest) plus a gate that stops sending after 1 s of silence.
- Choice: the phone side. `ReadStream` gets a `live` flag (readings pass false). `LiveWatch.Gate` is pure and
  unit-tested: it uses the 0.01 audible threshold, a 1-s hangover, and starts closed. The watch runs dry at each pause
  and catches up there.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Half-duplex phone mic while the voice is on the watch (1-s tail)
- Options: full duplex (the research assumed no echo); disable the sent track while the voice is audible plus a tail.
- Choice: half-duplex. The phone's echo canceller only knows what the phone plays (muted), and the watch speaker is
  on the wrist that often holds the phone, so the voice could interrupt itself or reach the chat as the user's words.
  This is 007's choice for the same problem. The cost is no barge-in. Nicholas may overrule it after trying.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The rules sit in a pure `LiveWatch` behind an `Out` interface
- Options: logic inside CodexLive (an upstream file, no JVM tests because of the WebView); a Google-free class with
  an `Out` that a fake implements (ReadQueue's pattern).
- Choice: `LiveWatch` with `Out`. The fake in `LiveWatchTest` is the second implementation. Stale callbacks, fallback
  and setting changes are the bug-prone parts, and they get tested. CodexLive only gains two hooks, a bridge method,
  the `pcm` branch and page JS.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - In a phone live call the rule picks a headset's own call device, confirmed, else the speaker (replaces the "headset (Android's routing) else the built-in speaker" entry above)
- Options: a media headset connected → Android's routing (round 0); always the speaker; select the headset's own
  communication device and check it took, else the speaker.
- Choice: the third (plan review round 1 F1). In communication mode Android never plays a call on A2DP, and Bluetooth
  gets it only over SCO that the app selects, so round 0's rule was the earpiece path with buds. `liveDevice` takes a
  wired, USB, BLE or hearing-aid call device, or a SCO whose name matches a connected media headset output (the watch
  has no media output, so its SCO never matches), else the speaker, never the earpiece. A refused device is skipped at
  once. A device that isn't Android's communication device 2 s after applying (`slipped`) counts as refused, or is
  applied again if it is the speaker, which also catches Chromium's later earpiece re-picks. All of it runs only
  while `noEarpiece`, so 001/016 picks keep today's code. Cost: name matching is unverified on the device (D8).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Auto's watch decision in a call uses connected outputs only (`livePick`)
- Options: `target()` as for read aloud (round 0); the same `pick` with `headsetIn(null, getDevices(OUTPUTS))`.
- Choice: the second (round 1 F2). `target()`'s API 33 routed answer follows the device Buddy set in communication
  mode and hides a connected headset, so a Speaker → Auto switch with buds would have sent the voice to the watch.
  016's normal-mode `target()` is untouched.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The phone mic reopens from the watch's estimated playback, decided in Java; one 0.01 threshold (replaces the "Half-duplex ... (1-s tail)" entry's timing)
- Options: the page's production time plus 1 s (round 0); a fixed worst-case tail (≈3 s, every turn waits); a
  watch-side "audible" message (exact, needs a watch update over adb from a computer); a phone-side copy of the watch
  `Speaker`'s `audibleUntil`, from each block as it is written into the channel.
- Choice: the phone-side copy (round 1 F3). `toWatch()` returns 0/1/2, and the mic is closed while an audible block
  isn't written yet, or until the playhead's last audible end plus 1 s. The gate, the playhead and the mic all use
  0.01 (`Speaker.AUDIBLE`); the page's 0.02 only drives the bar. Half-duplex itself stays (entry above). Residual:
  audio hidden in the Data Layer's buffers (Risk 1, D2b); the follow-up would be the watch-side signal.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The call's phone queue breaks past 1 s instead of dropping the oldest past 2 s (replaces that part of the "Bounded delay" entry above)
- Options: live `PcmOut`, 2 s, drop-oldest (round 0); non-live, 1-s cap, overflow = broken.
- Choice: the second. A watch that falls behind everything the transport buffers plus 1 s can't hold a conversation,
  so the voice moves to the phone (the brief's fallback). It is today's non-live `PcmOut`, so `ReadStream` needs no
  `live` flag, and the mic estimate has no dropped audio to account for. Cost: the voice is lost for ReadStream's 3-s
  grace before the fallback.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The live-call part of Watch is Android 12+, as 001's routing
- Options: a legacy `setSpeakerphoneOn`/`startBluetoothSco` path for API 30; the live part only on API 31+.
- Choice: API 31+ (round 1 F4), the facade's `begin` returns below it. This follows 001's decision for the same
  reasons: deprecated calls, a second code path, and the only target phone is API 31+. On Android 11 a phone live call
  stays as today, and the Watch texts say "Android 12+". Nicholas may overrule it (plan Open questions).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Live route attempts are bounded by a refused set; no safe device ends the call (replaces the refusal part of the "headset's own call device" entry)
- Options: retry by calling `reconcile()` again with one `refused` key (round 1: two refused devices alternate
  forever, review round 2 F1); a set of refused keys and a loop. With no safe device left: `drop()` to Android's
  routing (the earpiece), mute the page, or end the call.
- Choice: pure `liveAttempt` loops over `liveDevice` with `liveRefused`, a set kept until `use()`, so each device is
  tried once and the loop ends (buds → speaker → none). The live rule is its own `reconcile()` branch with no `drop()`.
  With no device left, the call ends with "the phone speaker couldn't be used", posted once, because ending says why,
  and a muted page would be a silent call with no reason given. A refused built-in speaker would be a device fault
  that hasn't been seen on any device.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Each utterance of the call's voice is one 012 reading; the mic reopens on the watch's answer (replaces the "phone-side copy of audibleUntil" entry, the "1-s breaking cap" entry and the open-ended call stream)
- Options: round 1's open-ended stream with a phone-side playhead from completed writes (blind to audio held in the
  Data Layer, review round 2 F2); a watch-side "audible" message (exact, a watch update over adb); a fixed long tail
  (no bound to size it); one 012 reading per utterance, cut by the silence gate.
- Choice: one reading per utterance. The installed watch app closes a reading with 2 after its playhead reached the
  last frame and the track was released, 0 after its player was stopped and flushed or broke, and 1 when nothing
  sounded. That answer is the watch's own "quiet now", with no watch update. The mic is closed from the first audible
  block until the answer plus 300 ms. The next reading opens only after the answer (a new channel replaces the playing
  one on the watch), and blocks that come meanwhile are held. A setting change mid-utterance finishes the reading and
  waits for its answer. A watch that sends no answer for 30 s after a reading's end is given up: its channel is
  cancelled and the mic opens 1 s later. This removes `openCall`, `CALL_CAP`, `PcmOut`'s `sent` and the estimate; the
  link only gains an `attached` callback. Costs: a channel and a 300 ms prebuffer per utterance after a pause, the
  watch's notification per reading, and lag inside one long utterance isn't bounded (it only delays the voice).
- By: Planner (claude-opus-5-5)

## Orchestrator decision after plan round 3 (no APPROVE)
Round 3's only open finding (F1, `broken`/disconnect taken as proof of silence) is accepted with a bounded fix rather than another planning round:
- Only an explicit watch answer (`played` code 2, or hush code 0 *while the channel is still attached*) counts as silence → mic reopens after TAIL (300 ms).
- A locally synthesized `broken`, a disconnect, or the 30-s give-up are failures: the mic stays closed for FAIL_TAIL = 1500 ms (the watch Speaker's buffer is 1 s, `Speaker.java:28-29`, plus margin), then reopens. ReadStream gets the smallest change needed to tell the coder which case occurred (e.g. a flag on `closed`), if it can't be told apart otherwise.
- Tests: LiveWatchTest covers a local `broken` with no answer, and a disconnect during the final drain after a mid-utterance setting change; both keep the mic closed ≥ FAIL_TAIL.
Why: the residual risk is bounded by the watch's 1-s buffer; a receiver-stop ack would need a watch update, which costs more than it's worth here. This overrides plan.md where they conflict.

## 2026-11-17 - Coder: how the failure tail (orchestrator decision, plan round 3) is carried, and two small calls
- Options: a flag on `ReadStream.closed` only; a new `Events` method every implementer must have; a default method.
- Choice: `ReadQueue.Events.broken(boolean watchStopped)`, a default that calls `broken()`, so ReadQueue and the 012 tests
  are untouched. `ReadStream.closed(local, code, lost)` (the old two-argument form stays, `lost = false`) settles code 0
  on a live link as "stopped" (the watch's own hush: its Speaker is silent). `WatchVoiceLink` passes `lost = reason ==
  CLOSE_REASON_DISCONNECTED`, since a disconnect carries code 0 too. A locally synthesized `broken` (PcmOut's grace
  expiry), a disconnect and any other code are not acknowledgements. `LiveWatch`: played, refused and stopped reopen the
  mic after `TAIL_MS` (300); broken, a disconnect and the 30-s give-up after `FAIL_TAIL_MS` (1500), which replaces the
  plan's `CANCEL_TAIL` (1000). A failure of an open reading counts only if it had been fed audio.
- By: Coder (claude-sonnet-5-5), on the orchestrator's decision.

## 2026-11-17 - Coder: the live branch of `reconcile()` also respects `other`
- Choice: the branch needs `!other` besides the NORMAL/IN_COMMUNICATION mode, so a phone or app call that was on
  before Buddy's voice started is left alone, as today's lines do (plan: "ringing or in a phone call: today's lines").
- By: Coder (claude-sonnet-5-5)

## 2026-11-17 - Coder: names and the check used
- `LiveWatch` is the static facade (`begin/end/pcm/toWatch/noEarpiece`) and `LiveWatch.Call` the instance the tests
  build with a fake `LiveWatch.Out`. No Gradle or Android SDK on the phone: the JVM tests were run with a javac/JUnit
  script over the real sources (github 171 tests, fdroid 147), against the Wear stubs from the earlier pipeline runs; the
  build, lint and CI steps are CI-only. The page JS was only syntax-checked with node.
- By: Coder (claude-sonnet-5-5)
