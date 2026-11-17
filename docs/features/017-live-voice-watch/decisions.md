# Decisions: 017-live-voice-watch
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
