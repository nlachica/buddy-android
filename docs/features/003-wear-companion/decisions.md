# Decisions: 003-wear-companion
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-15 - The phone's watch link lives in `app/src/watchlink/`, added only to the github source set
- Options: (a) a separate folder wired into the github source set (java, res, manifest); (b) `app/src/github/`
  (standard flavor folder); (c) `app/src/github/` plus an exclusion rule in build.sh; (d) a separate phone APK.
- Choice: (a). fdroid never compiles it, and build.sh (javac against android.jar, `app/src/main` +
  `app/src/$FLAVOR/java`) never sees it, so `FLAVOR=github ./build.sh` keeps working unchanged. Its APK has no watch
  link, which it couldn't have anyway: no Gradle, no Play services. (b) breaks build.sh's github build; (c) puts a
  rule in a script where a folder needs none; (d) can't work: the Data Layer needs the watch's package, and the link
  needs ChatHub in-process.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Plain MessageClient JSON under `/buddy/*`; the phone pushes heard/step/reply/done/error
- Options: (a) MessageClient both ways, one path per message kind, the phone pushing a run's messages as they come;
  (b) `DataClient` synced chat list + messages; (c) `MessageClient.sendRequest` RPC; (d) one path with a `type` field.
- Choice: (a). Small text fits MessageClient (no persistence needed: the phone chat keeps everything), and a run's
  several replies are a stream anyway. `heard` acknowledges every send, so both speech paths look the same
  downstream. ChannelClient stays for audio (path B, phase 2's `/buddy/live`). No version field: both APKs come from
  one CI run.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Watch app: module `:wear`, applicationId `app.buddy.assistant`, namespace `app.buddy.assistant.wear`, plain Java views
- Options: (a) plain `Activity` + views in code, Play services the only dependency; (b) Compose for Wear (Kotlin);
  (c) androidx.wear widgets/ambient.
- Choice: (a). It matches the phone app (Java 8, no XML layouts, no Kotlin), and 2 screens need nothing more. The same
  applicationId and signing key are required by the Data Layer; a namespace of its own keeps its R class and
  activities apart from the phone's. Version and signing as `:app` (BuildInfo.java, `BUDDY_KEYSTORE*`).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - `android.useAndroidX=true` project-wide
- Options: (a) flip the flag in `gradle.properties`; (b) try a subproject override for `:wear` only.
- Choice: (a). Play services needs androidx.core, and AGP options are build-wide (a subproject gradle.properties
  isn't a supported way to set them; unverified, not worth the risk). `:app` fdroid has no dependencies, so its APK
  can't change. Step 1 proves it by comparing with main's fdroid APK before any main-code edit.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Speech input is a swappable step: A watch recognizer, else B phone transcribes; pending the Watch4 probe
- Options: (A) `ACTION_RECOGNIZE_SPEECH` on the watch, text sent; (B) watch records PCM → ChannelClient → phone feeds
  it to the user's dictation engine through a `MicServer` feed; (C) a cloud STT key on the watch; (D) build both.
- Choice: A if the probe gets text back, else B. A is about 25 lines with no mic permission. B is about 150 lines
  and reuses the phone's engines, whose PCM format already matches. C is a new key, a new service and a privacy
  change; D is YAGNI. The phone's `heard` reply makes everything after this step identical either way. The final
  pick is the orchestrator's, on the probe result.
- By: Planner (claude-opus-5-5); pending probe

## 2026-11-15 - A watch message needs Buddy's accessibility service; a watch run holds a wake lock and stays quiet on the phone
- Options: for "Buddy off": (a) refuse with a clear message; (b) send anyway. For the run: (a) partial wake lock
  "buddy:watch", 15 min cap, plus `Session.watch` skipping phone read-aloud and the "run ended" notification;
  (b) no changes.
- Choice: (a) for both. Agent events only reach ChatHub through BuddyService's MCP server, so a run sent without it
  never answers; done-when 4 asks for a clear message. The wake lock copies notification rules' pattern for a run
  with the screen off. Done-when 3 wants the watch to speak, so the phone speaking too (read-aloud on), or Galaxy
  Wearable mirroring the phone's end notification as a second buzz, would be duplicates the feature itself causes.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - What reaches the watch: each BUDDY/ERROR message (spoken), STEP lines as status, done at run end
- Options: (a) forward each message as it comes; (b) only `ses.lastReply` at the end.
- Choice: (a), as the phone's own read-aloud speaks each BUDDY message. STEP lines give the watch a sign of life for
  slow models and approvals, and reset its 120 s screen-on clock. Text is cleaned for speech on the phone with a
  `Voice.spoken()` extracted from `Voice.speak` (one copy, localized "code"/"link" words) and cut at 3000 chars.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The fork gets its own release key, stored as the fork's CI secrets; needs Nicholas's OK
- Options: (a) new keystore → `BUDDY_KEYSTORE_BASE64`/`_PASSWORD` secrets on nlachica/buddy-android, backed up where
  Nicholas says; (b) local-only signing by the orchestrator; (c) debug keys.
- Choice: (a). CI then signs the phone and watch APKs alike (a Data Layer requirement), and the fork's
  reproducibility check passes. (b) leaves CI artifacts uninstallable; (c) debug keys differ per machine and
  wouldn't pair. Done by the orchestrator after his OK, never written into files.
- By: Planner (claude-opus-5-5); needs Nicholas
