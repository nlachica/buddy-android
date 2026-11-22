# "Hey Google" (or another wake word) → Buddy live voice / Tasker: every route, Nov 2026

## First, two facts that change the question
- **The phone isn't on One UI 7.** It's an **SM-G990W** (Galaxy S21 FE, Canadian Snapdragon 888) on **Android 16 / One UI 8.0** with the **2026-10-05** security patch, and Knox is intact (warranty bit 0). I read this on-device with `getprop`.
- **Google Assistant is gone from phones.** It was retired starting 2026-09-04, so "Hey Google" now only reaches **Gemini**. Anything that relied on Assistant is dead: AutoVoice, App Actions, the IFTTT Assistant trigger, and "run X in Tasker". Gemini doesn't read App Actions (`capabilities.xml`) or static-shortcut built-in intents, so building those into Buddy is wasted work. [TNW][AA][Google AI forum]
- **The hotword already runs on the low-power chip.** `dumpsys voiceinteraction` shows Google's `GsaHotwordDetectionService` bound with `DetectorType=trusted_hotword_dsp`, meaning detection happens on the phone's low-power sound chip (DSP). So any route that keeps Google as the default assistant adds **no extra battery drain**.
- **Making Buddy the default assistant (Settings → Default apps) costs "Hey Google".** Google's hotword only runs while Google is the default assistant. Keep Google as the default.
- **Buddy already has the entry point we need.** `AssistActivity` (`app.buddy.assistant/.AssistActivity`, exported, `ACTION_ASSIST`) starts listening, or starts Live voice if Settings → Voice is set to Live voice. Every route below just needs to launch it.

## Routes, ranked (extra battery / reliability / effort)
| # | Route | Battery | Reliability | Effort |
|---|---|---|---|---|
| 1 | **"Hey Google, open Buddy Voice"**: a second launcher entry that Gemini opens by name | none | high once tested; may need unlock | small |
| 2 | **"Hi Bixby, open Buddy Voice"**: same launcher entry, Samsung's wake word | none (Bixby's own DSP wake-up) | medium-high | none after #1 |
| 3 | **"Hey Google, text myself buddy go"**: Tasker Received Text / Notification profile fires a task | none | high, 3-6 s lag, uses an SMS | 10 min, no code |
| 4 | Gemini → Google Home routine → bridge device (Home Assistant or SmartThings virtual switch) → ntfy/Join/AutoRemote → Tasker | none on phone | low-medium: several clouds, 3-8 s; Gemini-for-Home broke custom starters (Dec 2025); community says scripts can't start from Gemini | high |
| 5 | Gemini Connected Apps / **AppFunctions**: Buddy exposes `startLiveVoice`, Gemini calls it | none | not available: Gemini integration is a private preview (Oct 2026 console) | medium, later |
| 6 | Software wake word in Buddy or Tasker (Porcupine, openWakeWord, Home Assistant microWakeWord, Snowboy "Hotword Plugin") | **high**: main processor + mic always on; mic indicator stays lit; Samsung kills it | medium | medium |
| 7 | Buddy as a VoiceInteractionService using the DSP | — | **impossible** for a normal app | — |
| 8 | Root (Magisk/LSPosed hook on Google's hotword) | none | **can't be done on this phone** | — |

### Notes per route
1. **The Buddy fork feature (recommended).** Add an `<activity-alias android:name=".VoiceLauncher" android:targetActivity=".AssistActivity" android:label="Buddy Voice">` with `MAIN` + `LAUNCHER` to the manifest. That's about 10 lines: a second icon called "Buddy Voice" that goes straight into Live voice. Gemini supports "Open [app name]" (Gemini help). Not yet verified: whether Gemini matches an alias label rather than the app name, and whether it opens apps while the phone is locked (Gemini says some locked-screen tasks still ask you to unlock). **Test this without code today:** use Tasker App Factory to export a one-action task (Send Intent to `AssistActivity`) as a standalone app named "Buddy Voice", then say the phrase. App Factory also gives a voice entry point for any Tasker task ("open Lights Off").
2. **Bixby.** "Hi Bixby" runs on Samsung's own wake-up service (`com.samsung.android.bixby.wakeup` 2.3.79 is installed) and can coexist with "Hey Google". Bixby opens apps by name ("Open calendar", Samsung guide). Quick Commands are gone since One UI 7; Samsung now points to Routines with an "Ask Bixby" action instead. Reports that Bixby can start a routine by its name are unreliable. "Open <app>" is the dependable verb. Bixby has no direct Tasker intent; reach Tasker through an App Factory app or an app shortcut inside Routines.
3. **Message-to-self.** The most obscure route, and fully working today. Gemini sends texts from the lock screen (that feature left beta). Tasker profile: *Event → Received Text*, sender = own number, content `buddy*`. The task launches `AssistActivity` or anything else, and the rest of the message can pass parameters. A WhatsApp or Telegram note-to-self caught by a Tasker *Notification* profile avoids SMS.
4. **Google Home.** Gemini on the phone can start household routines (Gemini help). Google Home has no webhook action, so you need a device that relays the trigger. Only worth it if Home Assistant is already in the house.
5. **AppFunctions.** This is the clean long-term version of #1. The phone already ships `com.android.extensions.appfunctions`. Revisit when Google opens Gemini's third-party AppFunctions beyond trusted testers.
6. **Battery numbers.** Picovoice publishes no phone battery figures, only CPU use (0.6% on a Raspberry Pi 5) and advises measuring on your own device. A third-party wiki quotes about 1-3% battery per hour for similar engines (unsourced). Home Assistant's own docs: on-device wake word "has a noticeable impact on battery life… uses more battery than 'Ok Google', [which] has access to low-power hardware". Upstream Buddy declined a wake word (issue #18).
7. **Why the DSP is off-limits to Buddy.** Since Android 12, `AlwaysOnHotwordDetector` (the DSP path) is a system API. Enrolling a DSP keyphrase and running `HotwordDetectionService` need privileged permissions (`MANAGE_VOICE_KEYPHRASES`, `MANAGE_HOTWORD_DETECTION`), and only the active assistant gets them. A Play-installed Buddy can't use the DSP, even as the default assistant. Becoming default would also switch off "Hey Google". [AOSP 12 notes][AOSP source]
8. **What root would cost on this phone.**
   - North American Snapdragon Samsungs have **locked bootloaders with no official unlock**. Paid exploit services (SamPWND/UNSAMLOCK) don't list the G990W.
   - "Root My Galaxy" (temporary root, said not to trip Knox) needs the June 2026 patch or older. This phone is on October 2026.
   - If root ever happened by unlocking: Knox e-fuse 0x0 → 0x1 permanently. That means **Samsung Wallet/Pay, Samsung Pass, Secure Folder** are gone for good. Banking apps need ongoing Play Integrity spoofing that breaks with Google updates. Warranty is void.
   - Even then there's no maintained module that reroutes Google's DSP hotword to a custom app. **Don't.**

## Recommendation
1. **Build #1 as a small Buddy fork feature**: a "Buddy Voice" launcher alias for `AssistActivity` (Live voice). Prove it first today with a Tasker App Factory "Buddy Voice" app. It reuses the hotword already running on the DSP, adds zero battery, and works with both "Hey Google" and "Hi Bixby". Unknowns are only Gemini's label matching and locked-screen behaviour; that's a two-minute test.
2. **Set up #3 in Tasker** as the general "voice → any Tasker task with parameters" route: "Hey Google, text myself buddy lights off". Zero battery, no code, works on the lock screen.
Skip App Actions, AutoVoice, IFTTT, a software wake word and root.

## Sources
- Assistant phone shutdown: https://thenextweb.com/news/google-assistant-shutdown-september-4-gemini-replacement · https://androidauthority.com/google-now-killing-assistant-gemini-3716545 · https://cybernews.com/tech/google-assistant-discontinued/
- App Actions not working in Gemini: https://discuss.ai.google.dev/t/how-to-invoke-custom-dynamic-shortcuts-and-app-functions-with-gemini/103215
- AppFunctions / Connected Apps: https://9to5google.com/2026/02/25/android-appfunctions-gemini/ · https://9to5google.com/2026/10/28/gemini-google-connected-apps-platform/
- Gemini open apps / lock screen / routines: https://support.google.com/gemini/answer/14579631 · https://support.google.com/gemini/answer/14576209 · https://www.androidcentral.com/apps-software/gemini-lock-screen-call-text-leaves-beta · https://support.google.com/gemini/answer/15335456
- Google Home scripts: https://developers.home.google.com/automations/starters-conditions-and-actions · https://www.googlenestcommunity.com/t5/Home-Automation/assistant-event-OKGoogle-starter-just-doesn-t-seem-to-work/m-p/747675 · https://9to5google.com/2025/12/17/gemini-for-home-reportedly-breaks-automations/
- IFTTT Assistant changes: https://9to5google.com/2022/08/02/google-assistant-ifttt/
- Bixby: https://www.samsung.com/ae/support/mobile-devices/why-is-bixby-quick-commands-not-working-and-why-have-the-feature-menus-disappeared/ · https://www.samsung.com/us/apps/bixby/bixby-user-guide · https://eu.community.samsung.com/t5/galaxy-note-series/how-to-trigger-a-bixby-routine-with-voice/m-p/1550151/highlight/true
- Hotword APIs: https://source.android.com/docs/whatsnew/android-12-release · https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/HotwordDetectionService.java · https://source.android.com/docs/automotive/voice/voice_interaction_guide/app_development
- Wake-word battery: https://picovoice.ai/docs/faq/porcupine/ · https://www.home-assistant.io/voice_control/android/ · https://community.home-assistant.io/t/setting-up-a-100-local-smart-speaker-on-an-android-tablet-using-tasker-and-snowboy-to-handle-wake-word-detection/611435
- Gemini + recorded "Hey Google" Tasker hack: https://www.androidpolice.com/i-created-task-routine-to-dim-my-room-bulb-when-my-phone-charges/
- Root on Samsung: https://www.thecustomdroid.com/samsung-galaxy-bootloader-unlock-guide · https://www.thecustomdroid.com/sampwnd-unlock-bootloader-samsung-snapdragon/ · https://androidauthority.com/root-my-galaxy-samsung-temporary-root-exploit-3692681 · https://github.com/salvogiangri/KnoxPatch/releases
