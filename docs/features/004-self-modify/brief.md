---
type: brief
feature: 004-self-modify
created: 2026-11-15
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 004-self-modify

## Ask (verbatim, never edit)
Oo before we do that, next feature. For the fork, I want to allow buddy to modify itself and it's settings and such..the official version doesn't allow for that

## Goal
In the fork, let the agent working in a Buddy chat (1) read and change Buddy's own settings, including the ones
upstream keeps user-only (app permissions/trust levels, notification rules, scheduled tasks, skills, MCP servers,
background mode, the Linux tools switch, voice and so on), through a direct tool rather than by tapping screens; and
(2) change Buddy's own code: edit the fork's source, have it built and signed (the fork's CI with Nicholas's key), and
install the new version of itself. Every change is shown to Nicholas on one approval card first.

## Non-goals
- The agent answering, skipping or auto-approving its own approval cards, or changing how approval cards work
  (the confirm gate stays the user's in every case).
- Reading out secrets: API keys, tokens, passwords are never returned by the tool (writing a key Nicholas gives is
  out of scope unless the plan shows it is trivial and safe).
- Tapping Buddy's guarded screens: the settings tool replaces that; screen guards stay as they are.
- Anything in the F-Droid flavor that upstream would reject; the official app is untouched.
- A general plugin/extension system.

## Done when
- [ ] A tool lists Buddy's settings with current values (secrets masked) and changes one, after an approval card
      that names the setting, old and new value; denying leaves it unchanged.
- [ ] It covers the settings upstream keeps user-only (at least: per-app trust levels, notification rules,
      scheduled tasks, skills, MCP servers, background mode, Linux tools per chat) plus the ordinary ones.
- [ ] From a chat, the agent can change Buddy's code in /root/.buddy/work/buddy-fork (or a clone), get a signed APK
      built, and install it as an update of itself after one approval card; Buddy comes back up with chats and
      settings intact.
- [ ] Approval cards still can't be answered by the agent (tested).
- [ ] Fits Buddy's code style and UI; YAGNI; F-Droid build unaffected or the difference is deliberate and recorded.

## Clarifications
- 2026-11-15 Nicholas: "modify itself" = both: change settings AND edit its own code and update itself.
- 2026-11-15 Nicholas: ask once per change on an approval card (settings changes and installs alike).
- 2026-11-15 Nicholas: settings through a direct settings tool, not by tapping the settings screens. (Orchestrator
  stated, and he accepted: it can never answer its own approval cards.)
- Context: the phone still runs the official Buddy; the fork only installs over it after the switch (003 verify).
