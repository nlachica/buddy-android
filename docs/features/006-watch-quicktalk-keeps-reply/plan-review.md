# Plan review: 006-watch-quicktalk-keeps-reply
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Joining can still discard an earlier unconfirmed message - `plan.md:57-65` equates `waiting` with an established listener and increments `run` on every join, but `Talk.java:106,162-170` sets `waiting` before asynchronous registration completes, and `Phone.java:31-39` performs asynchronous discovery before checking the send's validity. If the second recognition returns during either operation, the join invalidates the first message's callback (`Talk.java:175-176`), so the first message is never sent; it can also send the second before registration succeeds. Even after the first send has left, its delayed `HEARD` can satisfy the second message's single `heard` flag (`Talk.java:200-203`), allowing the first `DONE` to end the wait while the second send is still in flight. This violates the two-messages-once requirement (`brief.md:24`) without requiring reordered incoming messages. -> Explicitly handle joining before the earlier message is acknowledged: preserve its registration/send callbacks and serialize later submissions until that acknowledgement, or provide equivalent per-submission tracking. Do not use `waiting` as registration completion or let an earlier acknowledgement confirm a later submission. Keep this watch-side; no phone protocol redesign is necessary. Add controlled checks for a reopen during registration/discovery and for `join -> first HEARD -> first DONE -> second HEARD/reply`, verifying both sends and replies survive once.

F2 [major] Failure of the added message still cancels the original answer - The shared wait setup in `plan.md:62-67` and the `noHeard`/`ERROR` rules at `plan.md:77-85` retain conversation-wide failure handling. The existing send-error callback calls `fail` (`Talk.java:164-165`), `noHeard` also calls `fail` (`:94`), and `fail -> end` removes the reply listener (`:257-269`). Thus, after the story has been accepted, a transient failure sending the added question, or its missing acknowledgement, ends tracking of the still-running story. The new path preserves the first answer only when the second submission succeeds, contrary to the goal of keeping the pending run tracked (`brief.md:13-16`). -> Separate failure of an added submission from completion/failure of the already accepted conversation: show the added-send error while retaining the original listener, speech queue and bounded wait/status monitoring until that conversation ends. Define how a `DONE` deferred during the added send is settled if that send fails, so recovery cannot leave a wait stuck. Add a check with an accepted long first run, a failed second send, and a subsequent first reply: the first reply must still show and speak once, with no automatic duplicate send.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - Words are now sent **one at a time**. `Talk.sender` becomes a `queue`: words said while earlier words
are still in flight (`!heard`) wait in it, and each is sent only once the words before it are **settled** (heard,
refused, not sent, or given up by `noHeard`). So a join never invalidates or overtakes the earlier send, and an
earlier `heard` can't confirm later words (only one is ever in flight). A join no longer bumps `run`. `run` now goes
up only on a new conversation, when words are given up, and when the wait ends. So late callbacks of given-up words
can't send them or settle the next ones. Every send waits on the stored listener-registration `Task`, so nothing is
sent before registration succeeds. The rule "`waiting` = listener registered" is gone. Your sequence (`join -> first
HEARD -> first DONE -> second HEARD/reply`) becomes: B queued, A heard, B sent, A's `done` deferred while B is in
flight, B heard, B's reply, B's `done` ends the wait. Checks: trace rows T1-T2 for the Code Reviewer, plus probe runs
P1/P3 on the Watch4 (plan, Test plan). Plan: Design B, Step 1, decisions.md.

F2: accepted - Failures are split. **Per words** (`settle(error)`: watch-side send error, phone refusal `/buddy/error`,
`noHeard`): the error is shown and said, and then the next queued words are sent; otherwise the wait goes on if the
phone has an **accepted** run (new static `accepted`: set by `heard`, cleared by `done`). Only with nothing accepted
does the wait end. **Conversation-wide** (registration failure, `ceiling`, lost contact `noStatus`, `status
running:false` with nothing in flight, the screen closed): end as in 003. A deferred `done` is settled by the words in
flight: their `heard` starts a run with its own `done`, and their failure finds `accepted` false and ends the wait, so
it can't get stuck. Nothing is ever resent. Checks: trace rows T3-T6, plus probe P2 (accepted story, failed second send,
story said once, no resend). Plan: Design B, Step 1, decisions.md.
