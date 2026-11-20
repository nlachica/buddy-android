<p align="center">
  <img src="docs/banner.png" alt="Buddy: Claude Code, Codex, OpenCode, Antigravity and Grok in a bubble on your Android phone" width="100%">
</p>

<p align="center">
  <a href="https://github.com/ghorbelhamdi/buddy-android/releases/latest"><img src="https://img.shields.io/github/v/release/ghorbelhamdi/buddy-android?style=for-the-badge&label=release" alt="Latest release"></a>
  <a href="https://github.com/ghorbelhamdi/buddy-android/releases"><img src="https://img.shields.io/github/downloads/ghorbelhamdi/buddy-android/total?style=for-the-badge" alt="Downloads"></a>
  <a href="https://f-droid.org/packages/app.buddy.assistant/"><img src="https://img.shields.io/f-droid/v/app.buddy.assistant?style=for-the-badge&logo=fdroid&logoColor=white" alt="Buddy on F-Droid"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <a href="https://www.producthunt.com/products/buddy-20"><img src="https://img.shields.io/badge/Product_Hunt-Buddy-DA552F?style=for-the-badge&logo=producthunt&logoColor=white" alt="Buddy on Product Hunt"></a>
  <a href="https://glama.ai/mcp/servers/ghorbelhamdi/buddy-android"><img src="https://glama.ai/mcp/servers/ghorbelhamdi/buddy-android/badges/score.svg" alt="Buddy MCP server on Glama" height="28"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache_2.0-blue?style=for-the-badge" alt="License: Apache-2.0"></a>
</p>

# Buddy

**A floating chat bubble that lets Claude Code, Codex, OpenCode, Antigravity or Grok use your Android phone.**

Type or say what you want, like "reply to Sam that I'm running late" or "add a Switch 2 to my Amazon cart". Buddy reads the screen, taps and types to get it done, and asks you before anything is sent, posted, bought or deleted.

It runs inside the app, on your own plan: [Claude Code](https://docs.claude.com/en/docs/claude-code) with your Claude plan, Codex with your ChatGPT plan, Google's [Antigravity CLI](https://antigravity.google) with your Google account, xAI's [Grok Build](https://x.ai/build) with your Google or X account, or [OpenCode](https://opencode.ai) with its free models and no account at all. No PC, no adb, no root, and no API key needed (you can add keys for other services, such as DeepSeek or OpenRouter, and use them with Claude Code, Codex or OpenCode).

<p align="center"><a href="https://github.com/ghorbelhamdi/buddy-android/releases/latest/download/buddy.apk"><b>Download the APK</b></a> · <a href="https://f-droid.org/packages/app.buddy.assistant/">F-Droid</a> · <a href="https://ghorbelhamdi.github.io/buddy-android/">Website</a> · Android 11+, 64-bit</p>

<div align="center">

https://github.com/user-attachments/assets/d07936fc-4788-4ab5-a105-31b3e9aeba4f

<sub>Codex turns on the lights while OpenCode's free model adds a Switch 2 to the Amazon cart (real recordings, waiting sped up)</sub>
</div>

<table>
  <tr>
    <td width="33%" align="center"><img src="docs/screens/chats.jpg" alt="Chats with different agents"><br><sub>Chats with any agent</sub></td>
    <td width="33%" align="center"><img src="docs/screens/chat.jpg" alt="A chat with grouped tool calls"><br><sub>Tool calls fold into one line</sub></td>
    <td width="33%" align="center"><img src="docs/screens/settings.jpg" alt="Settings"><br><sub>Setup guide and themes</sub></td>
  </tr>
</table>

## What it can do

| | |
|---|---|
| **Uses your apps** | Messages, shopping, smart home, settings: anything on screen. Add instructions while it works. |
| **Asks first** | Approve, Deny or Change before anything is sent, posted, bought or deleted. |
| **Shows you how** | "Show me how to turn on dark mode": it points at each step and waits for your tap. |
| **Acts on notifications** | "When a customer asks on WhatsApp, reply with our opening hours", with your OK, a countdown you can stop, or right away. |
| **Voice** | Talk to it and have replies read aloud. |
| **Background mode** | The agent works in your apps on a screen of its own that you don't see, while you keep using your phone. Set up once with the phone's own Wireless debugging, no computer; watch it live in the chat. Optional: let it run `adb shell` commands on the phone, each one approved by you. |
| **Sub-agents** | "Compare this on eBay, Amazon and AliExpress": the agent starts sub-agents that each work in an app on a hidden screen of their own, at the same time, and report back. They only look things up; you see every screen in the chat. |
| **Background browser** | Web pages in a browser inside Buddy, without taking over your screen, even while the phone is locked. Watch and use it live in the chat; it marks what you need to do (sign in, a captcha) and never types your passwords. |
| **Builds apps** | Turn on Linux tools in a chat and "make me a calculator app" builds a real APK on the phone; the GitHub version also installs it for you. |
| **Phone tools anywhere** | The same tools as a local MCP server for your own Claude Code, Codex, OpenCode, Antigravity or Grok sessions, or over stdio with [mcp-bridge](mcp-bridge) for clients that start servers as commands. |

<p align="center">
  <a href="https://youtube.com/shorts/wPZvwy08BTE"><img src="docs/screens/sub-agents.gif" alt="Three sub-agents comparing a price on eBay, Amazon and AliExpress at the same time" width="320"></a><br>
  <sub>Three sub-agents, three shops, at the same time (<a href="https://youtube.com/shorts/wPZvwy08BTE">30 s video</a>)</sub>
</p>

> **Status: 2.3, early.** It works well day to day but has rough edges. Tested most on a OnePlus 12 (Android 16). Buddy is an independent project, not made by or affiliated with Anthropic, OpenAI, Google or xAI.

## How it works

```
Buddy app / bubble ──► Buddy's Linux (Alpine via proot): claude, codex, opencode, agy or grok
        ▲                                                     │
        └──────────── streamed replies ◄──────────────────────┤
                                                              ▼
Buddy accessibility service ◄──── MCP on 127.0.0.1 (phone tools)
```

Buddy is a normal Android app with an accessibility service, the kind of permission screen readers use. The agent runs in a small Linux inside the app and uses Buddy's phone tools (read the screen, tap, type, scroll, open apps) over a local MCP server. No adb or Wireless debugging, and it works without Wi-Fi.

## Setup

You need a 64-bit Android 11+ phone, plus a Claude, ChatGPT or Google account for those agents (Grok: a Google or X account). OpenCode's free models need no account.

1. **Install Buddy:** [download buddy.apk](https://github.com/ghorbelhamdi/buddy-android/releases/latest/download/buddy.apk) and open it, or get it from [F-Droid](https://f-droid.org/packages/app.buddy.assistant/). The APK updates itself; on F-Droid each version arrives a few days later, and F-Droid updates it for you. Both are the same app with the same signature, so you can switch between them without reinstalling. Buddy also installs the apps it builds for you. (Google Play turned Buddy down for production: its policy doesn't allow apps that use accessibility to carry out tasks.)
2. **Follow the setup guide** that opens on first launch: pick your agent, turn on the accessibility service, install and sign in, and try a first task.

**Install blocked or "Restricted setting"?** See [Setup in detail](docs/GUIDE.md#setup-in-detail).

## Local models

**On the phone itself:** Settings → Agents and models → **Models on this phone**. Download Gemma 4 or Qwen3.5 (1.2–5.2 GB) and Buddy runs it with llama.cpp on the phone's NPU (Snapdragon 8 Gen 2 and newer), GPU or CPU, offline. Chats with it use lite mode: a short prompt and a dozen phone tools, more when needed. On a OnePlus 12, Gemma 4 E2B on the NPU reads about 2,000 tokens/s and writes about 24/s: fine for simple requests like the battery or a timer, not reliable for long tasks.

**On your computer:** Run the model on your own computer and nothing goes to an AI company. Start [Ollama](https://ollama.com), LM Studio or llama.cpp's server on a PC (for Ollama, set `OLLAMA_HOST=0.0.0.0` and a context of at least 48k tokens, e.g. `OLLAMA_CONTEXT_LENGTH=49152`; OpenCode keeps a big part of the context free for the answer, and with less it fails before the first one). In Buddy, pick **Local model** in the setup guide (Settings → the setup card → *Open the step-by-step guide* opens it again later): it sets up OpenCode, asks for your server and makes it the default for OpenCode's chats. Or add it by hand: Settings → Agents and models → API keys → Add API key → **Ollama** (or LM Studio, llama.cpp). Either way, put the PC's address on your Wi-Fi instead of `localhost` and leave the key empty. Plain `http://` works on your own network and over Tailscale.

What to expect, from tests on an RTX 2060 Super (8 GB): Qwen3 8B and Qwen3 4B handle simple one-step requests, like the battery level or the phone's status. Multi-step tasks on the screen are another matter: in a calculator test the 4B tapped the wrong buttons and then said it had worked. For real tasks, use a bigger model that's good at tool calling. The first answer of a chat takes longest, since the model first reads about 13k tokens of instructions and tool descriptions.

## Updating

Install the new APK over the old one: chats, settings and sign-ins stay (don't uninstall first). You can also ask Buddy to *"update yourself"*, or let [Obtainium](https://obtainium.imranr.dev) or F-Droid do it ([how](docs/GUIDE.md#updating)).

## Galaxy Watch (Wear OS)

`buddy-watch.apk` is a small Buddy app for a Wear OS 3+ watch (made for the Galaxy Watch4). On the watch you pick one of your chats, tap and speak; the words go to that chat on your phone and run like a typed message, and Buddy's answer comes back to the watch, which shows it and reads it out with its own speaker. It works with the phone locked in a pocket. It needs Buddy running on the phone, so it does nothing alone. Approvals and settings are not on the watch (yet).

- **Replies in Buddy's voice:** when ChatGPT's voice is the phone's speech engine (Settings → Voice), the watch reads replies in that voice instead of its own: the phone makes the audio and streams it to the watch speaker. If that voice or the link fails, the watch's own voice reads the reply, once. It starts a few seconds later than the watch's own voice (the phone sets up a voice call per burst of replies) and uses a little of your ChatGPT plan's voice allowance.
- **Opening the app:** the watch's app list opens the talk screen of your default chat, the last one you talked to or that answered you, with the mic and call buttons (nothing is listening yet). Tap the chat's name at the top to pick another chat. At the top of the list, **New chat** listens right away; what you say first starts a new chat on the phone, with your default agent and settings. **Quick talk:** to go straight to listening, point the Home double-press at Buddy once, over adb: `adb -s <ip:port> shell settings put system setting_home_key_double_press app.buddy.assistant/app.buddy.assistant.wear.QuickTalk` (the setting's name and format may differ by firmware; read the current value first).
- **The conversation:** the talk screen shows the chat's latest messages (up to 30, yours and Buddy's), newest at the bottom and opened there. Swipe or turn the bezel to scroll; the mic and call buttons stay on screen. New messages appear while it is open, also those from the phone. It needs the phone app from the same release; with an older one the screen works as before and says the phone isn't answering.
- **Calls:** the round sound-wave button next to the mic on the talk screen starts Buddy's live voice call in that chat, with the watch's microphone and speaker (first time: allow the microphone). It works with the phone locked, needs Codex signed in on the phone, and ends with End on the watch, swiping the screen away, End on the phone, or after 30 minutes. It is half-duplex: you can't talk over Buddy's voice, wait until it stops.
- **Read aloud on the watch:** Settings → Voice → Audio device → **Watch** (the GitHub version only; it needs the watch app from the same release) makes the replies that Buddy reads aloud in a phone chat play on the watch speaker instead of the phone, in the voice you chose (the phone's or ChatGPT's), with the phone silent. If the watch can't play it (not connected, busy with a talk or a call, or an older watch app), the phone reads it, after a spoken notice the first time. If the watch can't start its player from the background (Wear OS 12+), exempt the app once: `adb -s <ip:port> shell dumpsys deviceidle whitelist +app.buddy.assistant`.
- **Live voice on the watch:** with **Watch** picked (Android 12+, the GitHub version, the watch app from the same release; no watch update is needed), a live voice call started on the phone plays Buddy's voice on the watch speaker, with the phone silent; Auto does the same when no headset is connected and the watch is reachable. The phone's mic keeps listening, but it pauses while the voice speaks on the watch, until the watch has finished (you can't talk over Buddy there). If the watch can't play it or drops, the voice moves to a connected headset or the phone speaker, never the earpiece. When the watch can play again, the voice goes back to it at the next pause (up to 3 tries per call). Changing the audio device mid-call moves the voice. Calls started from the watch are as before.
- **Audio device Auto:** Settings → Voice → Audio device → **Auto** uses what phones use: a connected headset (Galaxy Buds, LS2 FreeCom, wired; the latest one), else the watch for read aloud (the GitHub version), else the phone speaker, checked when each reading starts. A device you picked is used while it is connected, Auto stands in while it isn't, and it is used again when it comes back. Settings shows what Auto is using now. A live voice call started on the phone (Android 12+) settles on one of these, never the earpiece: when the phone plays the voice, it uses the device you picked while it is connected (except Phone, the earpiece), else a connected headset, else the phone speaker. While you dictate, Auto uses a connected Bluetooth headset's microphone (its call link); read aloud stays on its media link. Android can play on the earpiece for a moment, not in the steady state: while a Bluetooth headset's call link connects (usually a second or two); if Android still reports the earpiece 5 seconds after Buddy picked a headset, Buddy asks for the phone speaker (a headset Android doesn't report yet gets 30 seconds while the speaker is reported); and if a headset's link drops while it stays connected, up to about 2 seconds. A headset that is unplugged or switched off is replaced at the next check, within a fraction of a second.

It talks to the phone over Google's Wear OS link, which only connects apps with the same package name and the same signing key. So:
- the phone needs `buddy.apk` (the GitHub version, not F-Droid's) **from the same release** as `buddy-watch.apk`, signed with the same key. The official Buddy can't talk to the watch app;
- install both from the same release when you update (phone: open `buddy.apk`; watch: `adb install -r buddy-watch.apk`). Buddy's own updater only knows the official repository, so turn off Settings → Update notices and don't ask Buddy to update itself on a build you signed yourself;
- an APK made with `build.sh` has no watch link.

Install on the watch with adb over Wi-Fi: on the watch, Settings → Developer options → ADB debugging and Wireless debugging. Then, on a computer on the same network:

```
adb pair <ip:port>            # the pairing address and code are shown on the watch (Wear OS 4+)
adb connect <ip:port>         # the address on the Wireless debugging screen
adb -s <ip:port> install buddy-watch.apk
```

## Security

An accessibility service is powerful, so please read this.

- **Local only.** The phone tools listen on `127.0.0.1` with a random per-install token.
- **Buddy enforces the approvals, not the agent.** Messaging, shopping and money apps start on a hard limit: Buddy itself stops on buttons like Send, Pay and Delete until you OK them.
- **Prompt injection is the main risk.** A message or web page could try to give the agent orders. On-screen text is treated as content, and the approval card is the backstop, so read it.
- **No passwords.** Buddy never types passwords, codes or payment details; it hands those to you.
- **What leaves the phone:** only what the agent sends to its provider (Anthropic, OpenAI, Google, xAI, OpenCode's service or the provider you connected) to answer you.

More in [Security in detail](docs/GUIDE.md#security-in-detail). Found a problem? Please open a private security advisory instead of a public issue.

## FAQ

**What does it cost?** Buddy is free. The agent uses your plan's limits, or nothing at all with OpenCode's free models (no account) or Antigravity's free plan (Google account).

**iPhone?** No. iOS doesn't let apps operate other apps.

**Which phones?** 64-bit Android 11 or newer. On some brands, set Buddy's battery use to *Unrestricted*.

**Does it need a computer, adb or root?** No.

## Learn more

- [User guide](docs/GUIDE.md): chats, models, voice, notification rules, the bubble, Remote Control, SSH and Linux tools
- [Benchmark](benchmark/README.md): which model uses a phone best
- [Privacy policy](docs/PRIVACY.md)

**In the press:** [ProPakistani](https://propakistani.pk/2026/10/12/this-free-android-app-lets-claude-code-use-your-phone/), [The Neutral](https://theneutral.pk/this-free-android-app-lets-ai-control-your-phone-without-a-pc/), [Trendshift](https://trendshift.io/repositories/289557) (October 2026)

## Contributing

Bug reports, ideas and translation fixes are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). If Buddy is useful to you, a ⭐ helps other people find it.

## For developers

```
git clone --recurse-submodules https://github.com/ghorbelhamdi/buddy-android.git
./gradlew assembleRelease     # Android Studio / Gradle
./build.sh                    # on the phone in Termux, no Gradle → build/buddy-release.apk
```

proot and libandroid-shmem are git submodules in `third_party/` (`git submodule update --init` in an existing clone). `build.sh` needs `pkg install openjdk-17 aapt2 d8 apksigner python clang make` and android.jar from platform-36 at `~/android-sdk/android-36/android.jar`. It creates a signing key at `~/.buddy-keys/release.jks` on first run: back it up, since updates must be signed with the same key. Gradle release signing reads `BUDDY_KEYSTORE` and `BUDDY_KEYSTORE_PASSWORD` (optionally `BUDDY_KEY_ALIAS`, `BUDDY_KEY_PASSWORD`). `build.sh` builds the phone app without the watch link (it needs Google Play services, which `build.sh` can't fetch); Gradle's GitHub build and `wear/` have it. The version lives in `BuildInfo.java`, and the project layout is in [CONTRIBUTING.md](CONTRIBUTING.md#project-layout).

## License

[Apache-2.0](LICENSE). Geist fonts: SIL Open Font License. Icons: Material Symbols (Apache-2.0). Bundled proot (GPL-2.0), talloc (LGPL-3.0) and libandroid-shmem (BSD-3-Clause): see [third_party](third_party/README.md) for licences and source.
