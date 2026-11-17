# Plan review: 023-idle-chat-delivers-messages
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Failed live delivery can lose a message when the old run ends - The plan removes text from notes before the asynchronous `say` finishes, then reuses the existing leftover handler on failure (`plan.md:119-127`, `plan.md:199-204`). That handler clears notes and posts a continuation which silently does nothing if another run has started (`app/src/main/java/app/buddy/assistant/ChatHub.java:885-902`); helper completion runs on a separate thread (`app/src/main/java/app/buddy/assistant/Helper.java:78-103`). Concrete ordering: message A is being sent live; message B becomes a note; `buddy_done` drains B and posts its continuation; A's already-queued failure callback runs before that continuation, sees the session idle and posts A's continuation; B starts a run, then A's continuation skips delivery because `ses.running` is true. A is now in neither notes nor the agent's input. This violates done-when 3 and is not covered by the proposed flag-only Java tests or shell happy-path tests (`plan.md:194-198`, `plan.md:214-217`). -> Make the shared leftover handler retain ownership of text until it is delivered or restored: re-evaluate the current session/run when the posted continuation executes, queue or route the text if another run has started, and preserve Stop/deletion handling. Specify a deterministic test for the ordering above, plus failure callbacks on either side of `buddy_done`, proving each message is delivered once or restored after Stop.

F2 [minor] Flushing every note also sends notification-rule notes live - The plan explicitly excludes notification-rule runs from changed behavior (`plan.md:22`, `decisions.md:54-60`), but the unconditional result-time flush joins and sends all `ses.notes` (`plan.md:125-127`). Today rule sends and user sends reach the same note collection (`app/src/main/java/app/buddy/assistant/ChatHub.java:262-271`, `app/src/main/java/app/buddy/assistant/ChatHub.java:299-301`, `app/src/main/java/app/buddy/assistant/ChatHub.java:942-951`). Keeping the rule branch as a plain note at insertion therefore does not preserve its behavior at the next result. -> Preserve the stated boundary when draining notes, retaining enough source information to leave rule notes on their existing path; include a mixed rule/user-note case. Alternatively, explicitly resolve this scope change in the plan and decisions rather than claiming rule delivery remains unchanged.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted. Text the agent never got now has one owner, `leftover(ses, text)`, which decides when it runs, not
when the text was taken. A static `placeLeftover` makes the route and the state change. Deleted chat
(`sessions.all.contains`): dropped. Running (another run started, or this one still goes): back into `ses.notes`.
Stopped: appended to `ses.unsent`. Otherwise `sendNoteAsMessage`. `buddy_done`'s inline Stop/continuation code
(`ChatHub.java:885-902`) becomes a post of `leftover`. Today's `if (!ses.running)` skip, which dropped the text, is
gone. `sendLive`'s failure (non-zero exit, or `Helper.run` throwing) calls the same `leftover` on the main thread. In
your ordering, whichever of A's failure and B's continuation runs first starts a run, and the other joins that run's
notes. Two small fixes the same pass turned up:
- the failure path passes the raw text, so the "Added while you were working" prefix isn't doubled;
- `sendLive` no longer sets "Thinking…", since `system/init` sets it (`ChatHub.java:749-750`). A failed `say`
  leaves no stale status.

New test `LeftoverTest` (plain JUnit; `ChatHub` statics already load there, see `BypassTest`) covers the routes, both
orderings around `buddy_done` (each of A and B sent exactly once, nothing left), and the Stop variant (nothing sent,
both in `unsent`). Updated: plan.md Approach 4, Step 4, Test plan row 3; decisions.md entry "one owner".

F2: accepted, using your alternative: the scope is resolved, not tagged. A rule run never reaches a running chat.
Triggers starts one only when `runningCount() == 0` (`Triggers.java:366`), moves to a new chat if the rule's chat is
running (`:541`), and offers "Check again" only on an idle chat (`canRecheck`, `:717`). So every note is the user's
own. The plan drops the `prompt == null` carve-out: `send` is unchanged, and its running branch already calls
`addNote`. There is no mixed rule/user case to test. Updated: plan.md Non-goals, Approach 4 ("Rule runs"), Step 4,
Open questions; decisions.md entry "No rule carve-out" (revises "Scope of the live path").
