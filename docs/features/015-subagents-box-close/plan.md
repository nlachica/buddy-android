# Plan: 015-subagents-box-close
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal
In a chat, the Sub-agents box (`ChatActivity.helpersBox`, the cards of the chat's delegate round) gets a close
control (×) once every sub-agent in it is done. Tapping it hides the box until the next delegate round. That round
shows a fresh box as it does today. The control matches Buddy's look.

Done when: (1) all sub-agents done → × visible, a tap hides the box, and the reports stay reachable where they are
today; (2) while any sub-agent runs, there is no ×; (3) the next delegate round shows the box again, and CI is green.

Non-goals (brief): changing sub-agent behaviour, their reports, or the bubble panel's list (`Bubble.helpersList`).
Also out: a collapse state, a way to reopen a closed box before the next round, and a helper chat's own
"Sub-agent of …" row (it never gets a ×).

## Design
**The parent chat remembers which round's box the user closed. The round is the helpers' ids.**

1. **What a round is.** A delegate round deletes the old helpers and makes new sessions with new ids
   (`Helpers.java:89-95`). Nothing else adds a helper. A helper is deleted only with its round, its parent chat
   (`ChatHub.java:243`), or Setup's "remove old chats" for its agent. The chat list and the bubble never show helpers,
   so they can't be deleted one by one (`MainActivity.java:223`, `Helpers.java:104`). So the joined helper ids name a
   round, and a new round never matches an old key. That gives "until the next delegate round" without touching the
   delegate path.
2. **Two pure functions in `Helpers`**, which is the sub-agent module and is testable on the JVM:
   - `static String round(List<Sessions.Session> l)`: the ids joined with `|` (`""` for none).
   - `static boolean boxClosed(String closed, List<Sessions.Session> l)`:
     `!l.isEmpty() && !anyRunning(l) && round(l).equals(closed)`.
   - `anyRunning` drops `private` (package-private), so `ChatActivity` uses the same "done" test.
3. **State, saved with the chat.** `Sessions.Session` gets a saved field `String helpersClosed = ""`: the round
   whose box the user closed. `toJson` writes `helpers_closed` only when it isn't empty. `fromJson` reads it with
   `optString`. Both are separate statements, not added to the long `put` chain, to keep upstream merges easy.
   Saving it means the box stays closed when the chat is reopened, rotated, or Buddy restarts, until the next round.
   A stale key left over from an old round never matches again, so it never needs clearing.
4. **`ChatActivity.renderHelpers`**, parent case only (`parent == null`):
   - `closed = Helpers.boxClosed(ses.helpersClosed, l)`. Add `"closed|"` to the `ids` signature when it is true. A
     change in the closed state then goes through the existing "from scratch" path.
   - From-scratch path, when closed: `dropHelperCards()` (stops the 1 s picture loop and `Browser.watch`),
     `removeAllViews()`, `GONE`, return. When it opens again (a new round, or see Risks), the box is rebuilt as today.
   - From-scratch path, when open: the title becomes a row, the existing `sectionLabel` (weight 1) plus an
     `iconButton(this, R.drawable.ms_close, getString(R.string.helpers_close), MUTED)`. That is the house ×, as on a
     scheduled message (`ChatActivity.java:2007`, 40dp). The title line keeps its height, and the × is centred on the
     title's text, not on the label's padded box. The button goes in a new field `helpersClose`, which is set to null
     on every rebuild.
   - In-place block (runs on every refresh): `helpersClose.setVisibility(Helpers.anyRunning(l) ? INVISIBLE : VISIBLE)`.
     `INVISIBLE`, so nothing moves when it appears. The last card turns Done and the × appears in the same refresh.
   - Tap: read `l = Helpers.of(hub, ses.id)` again. If `anyRunning(l)`, do nothing. Otherwise
     `ses.helpersClosed = Helpers.round(l)`, `hub.sessions.save(ses)`, `renderHelpers()`.
   - The helper-chat case (`parent != null`) is unchanged.
5. **The chat's own screen.** The "This chat" card is in the box, so it goes with it. The existing line that hides
   `backgroundPanel` only applies while that card exists (`ChatActivity.java:870`). Once the box is closed, the chat's
   own hidden screen (if any) shows in its usual panel, as in a chat without sub-agents. No new code.
6. **Reports stay where they are.** Closing deletes nothing. The sub-agent chats live until the next round, as today.
   The bubble panel's rows still open them (`Bubble.renderHelpers` reads `Helpers.of`, not the box). Search lists
   them (`SearchActivity.java:122` skips only empty or incognito chats). The parent's own reply and steps are
   untouched.
7. **String:** `helpers_close` = "Close sub-agents" in `values/strings.xml` only. Fork features add English strings
   only (012, 013), and Android falls back to English.

Alternatives considered:
- Collapse to a header line: the brief says the tap hides the box.
- Collapse-only while running: the brief allows "no close"; this needs less code.
- A `boolean` on the parent, reset in `Helpers.delegate`: this touches the delegate path (non-goal), and the ids key
  needs no reset.
- A flag on each helper session: N saves per close, and the state is spread over N chats.
- An activity field: the box would come back on reopen or rotation (brief: until the next round).
- A runtime-only `Session` field: the old box would come back after a restart. Saving costs two lines.
- Clearing the close when a sub-agent runs again: this writes state during render, and does so only if the parent
  chat is open at that moment.

## Steps
1. **Round key and closed test.** `app/src/main/java/app/buddy/assistant/Helpers.java`: `anyRunning` becomes
   package-private; add `round(l)` and `boxClosed(closed, l)`, each with a one-line doc. New test
   `app/src/test/java/app/buddy/assistant/HelpersBoxTest.java` (cases in the Test plan).
2. **Saved field.** `app/src/main/java/app/buddy/assistant/Sessions.java`: `String helpersClosed = ""` in the saved
   fields, next to `parent`/`slot`, with a doc ("the round of sub-agents (Helpers.round) whose box the user closed in
   this chat; the next round's box shows"). Add one line each in `toJson` and `fromJson` (Design 3).
3. **The ×.** `app/src/main/java/app/buddy/assistant/ChatActivity.java`: `helpersClose` field and `renderHelpers`
   changes as in Design 4. `app/src/main/res/values/strings.xml`: `helpers_close` next to `helpers_title`.
4. **Docs.** `docs/GUIDE.md:134`, after "Tap a sub-agent to open its chat with its steps.", add one fork sentence:
   "Once they're all done, **×** on the box closes it until the next round; the bubble's panel still lists them while
   it shows that chat." Upstream's text stays as it is.
5. **Ship and check** (orchestrator, after review and a green CI): merge, BuildInfo `-fork.<n>`, tag,
   `update_buddy install=true`, then the device rows below. Fix forward if a row fails.

## Test plan
| Done-when | Proof | Where |
|---|---|---|
| 1. All done → ×, tap hides, reports reachable | `HelpersBoxTest`: `boxClosed` is true when the key matches and none runs. Device: ask a chat for two web-only sub-agents. When both cards say Done, × shows at the end of the "Sub-agents" title. Tap → the box is gone, and the messages move up. The parent's reply is unchanged. The bubble panel on that chat lists both, and a tap opens a sub-agent's chat with its report. Search for a word from a report → that sub-agent's chat. Leave and reopen the chat: still closed. Force-stop Buddy and reopen it: still closed (saved). TalkBack reads the × as "Close sub-agents". | unit; device after ship |
| 2. Running → no × | `HelpersBoxTest`: `boxClosed` is false while any runs, even with a matching key. Device: during the round, the title shows no × and the title line doesn't move when it appears. A sub-agent's own chat shows only "Sub-agent of …", with no ×. Code walk (Code Reviewer): the tap re-checks `anyRunning` on a fresh `Helpers.of`. | unit; device after ship; review |
| 3. Next round → box again; CI green | `HelpersBoxTest`: a new round (other ids) with the old key → `boxClosed` false, and `round` differs. Device: in the same chat, ask for another round → a fresh box with the new cards, no × while they run, and × when they are done. CI on the PR: `testGithubDebugUnitTest` (new test plus existing), `assembleGithubRelease assembleFdroidRelease :wear:assembleRelease`, F-Droid reproducibility, `helper-tests`. | unit; device after ship; CI |
| Edge: chat's own screen | Device, chat in background mode with its own screen in use: after ×, that screen shows in its usual panel (Show on my screen works). | device after ship (if background mode is set up) |

`HelpersBoxTest` cases:
- empty list → false;
- `closed = ""` → false;
- key matches, none running → true;
- key matches, one running → false;
- other ids with the old key → false;
- `round` of `[a, b]` is `"a|b|"`;
- `round` of an empty list is `""`.

Sessions are built with `new Sessions.Session(id, "claude")`, with `running` set directly. The coder runs the test
locally if Gradle works on this device; otherwise CI on the PR is the proof.

## Risks
- **A done sub-agent run again by the user** (its chat opened from the bubble panel or Search, then written in): the
  box shows while it works (no ×) and hides again when it is done, because the key still matches. This is intended:
  the user closed that round, and its work shows while it runs (decisions).
- **Closed box, own card gone.** If the chat's own screen is in use, its `BackgroundPanel` shows in place of the
  "This chat" card (Design 5). That is today's behaviour for a chat without sub-agents, not new UI.
- **Upstream syncs.** `renderHelpers` is upstream code, so a sync that rewrites it will conflict here. Keep the
  diff small and in one place. The `Sessions` lines are separate statements, and the field is fork-only.
- **`Helpers` and `Sessions.Session` on the JVM.** Both load under `isReturnDefaultValues`: `Session` is a static
  nested class with plain field initializers, and `Helpers`' statics are constants. If loading fails, the coder records
  it in `decisions.md`, and the Code Reviewer checks the two functions by reading.
- **Cached pictures while closed.** `helperPictures` keeps the last bitmaps until the activity ends or the next
  rebuild. That is no worse than today, which holds them while the box shows.

## Open questions
None blocking. One default Nicholas may want to change (decisions): after ×, the app chat has no way to bring the
box back before the next round. The sub-agents' chats stay reachable from the bubble panel and Search.
