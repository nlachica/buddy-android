# Plan review: 013-watch-new-chat
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Notification reopening loses the pending-new-chat state - The plan keeps `fresh` only in `TalkActivity`'s `"new"` extra and explicitly leaves the notification unchanged (`docs/features/013-watch-new-chat/plan.md:25-33,94-97`). However, `Talk` posts its notification before sending (`wear/src/main/java/app/buddy/assistant/wear/Talk.java:220-230`), and its content intent uses `Talk.intent`, which carries only chat/title/accent (`Talk.java:180-182,302-306`). Start a new chat, speak, leave with Home, then reopen that notification before HEARD (or while a failed-send error is being spoken: `Talk.java:395-409`): the new activity has `fresh=false` although the phone may have no chat. It exposes Call, and a call or another phrase writes the nonexistent id to `last` (`wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java:168,207`); after the first send fails, a retry also omits `new: true`, so the recovered phone refuses it as a missing chat. This defeats the planned no-ghost/default-chat protection and retry behavior. -> Carry the pending-new flag with the conversation into notification-created activities, clearing it when the phone confirms creation; keep call visibility, SEND's `new` field and `remember` consistent across reopening/recreation. A small state field and intent propagation suffice; retain the watch-generated id design. Add a regression check that reopens the notification before acknowledgment, exercises a failed first send, verifies Call stays hidden and the previous default stays intact, then retries after recovery and creates exactly one chat with that same id.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - the new-chat flag now travels with the conversation. `Talk` keeps `static boolean fresh` (set by
`begin`, which takes the screen's flag as a new parameter, and cleared at `HEARD`). `notification()` puts
`"new" = fresh` on its content intent. A screen opened from the notification before the phone has heard the words,
including while a failed first send's error is still being said, is therefore fresh like the one that sent them: Call
hidden, no send-time `remember`, and `new: true` on its SEND, so a retry after recovery creates the chat with the same
id. Every other way to a talk screen (the list, `last`) only holds ids the phone has confirmed, so the notification is
the one route that needed it. Recreation keeps the flag through the intent. The screen still clears it on a YOU line
for its chat, so a notification posted before `HEARD` doesn't keep Call hidden afterwards. The watch-made id design
stays. Changes: plan Design 2, Step 4 (`Talk.java`: field, `begin` parameter, notification extra, HEARD clear), a
new device row in the Test plan for done-when 2 (reopen from the notification during a failed first send: no Call,
previous default kept; retry after recovery: exactly one new chat, its reply on that screen), a code-walk item, one
Risk (process death leaves the screen fresh until words are heard, which is safe), and a decision in `decisions.md`.
