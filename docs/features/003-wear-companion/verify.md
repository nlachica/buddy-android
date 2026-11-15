# Verification: 003-wear-companion

| Done-when item | Evidence | Result |
|---|---|---|
| 1. CI builds phone + watch APKs | Run 43701551913 on feat/003-wear-companion (2026-11-15): helper-tests and apk green, release signing from the fork's own BUDDY_KEYSTORE secrets ; phone, watch and fdroid-check APKs all signed by cert 7cf74b84...; buddy-watch APK installed on the Watch4 (adb: Success) | pass |
| Speech path A gate (probe) | Watch4 SM-R870 over wireless adb, com.buddyapps.watchprobe: SpeechRecognizer available; RECOGNIZE_SPEECH handled by Google (com.google.android.tts) and Samsung keyboard (resolver shown when both); recognized "hello this is me talking"; TTS init ok, engine com.google.android.tts | pass: path A confirmed, B not needed |
| 2-4. Talk, busy-chat continuation, TTS, locked phone, wrist down | Needs the phone on a fork build signed with the new key (Nicholas: "not yet") | pending |
| 5. F-Droid APK has no Google code | CI step added in this branch ran green in run 43701551913 | pass (CI) |
| 6. Screenshots of the watch screens | pending with 2-4 | pending |

VERDICT: PARTIAL - code and build verified; on-device run waits for the phone switch to the fork.
