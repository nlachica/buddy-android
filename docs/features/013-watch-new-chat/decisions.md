# Decisions: 013-watch-new-chat
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - The watch makes the new chat's id; the phone creates the chat on the first SEND that carries `new: true`
- Options: (a) watch-made 12-hex id, phone creates on first words if missing (idempotent); (b) phone makes the id on
  the first SEND and the watch adopts it from `/buddy/heard`; (c) a separate `/buddy/new` round trip after speech;
  (d) create on tapping New chat.
- Choice: (a), because every watch part (Talk's filter, status, notification, `last`, call) already keys on one id
  known from the start, so only the send and the phone's `deliver` change. A repeated send with the same id is an
  ordinary send, so a retry after a lost HEARD can't make a second chat. (b) needs a pending-id state across `Talk`,
  `TalkActivity`, the notification and `last`, and duplicates on retry. (c) has two messages and a ghost window between
  them. (d) leaves an empty chat on every cancel or unreachable phone (done-when 2). The brief's "creates a new chat ...
  so the first spoken message starts that chat" holds: what the user sees is the same, minus the empty chat when
  nothing is said.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The new chat uses `Prefs.backend` and `Sessions.create`'s defaults, and isn't selected on the phone
- Options: select it (like the phone's New chat sheet and `Bubble.talk`) or not (like rule chats, `select=false`).
- Choice: not selected, because the user is on the watch. A watch send to an existing chat doesn't move the phone's
  bubble either, and the new chat is at the top of the phone's list anyway. Defaults come from one
  `Sessions.create(id, backend, select, dev)` overload (today's body, unchanged), so upstream changes to new-chat
  defaults apply to the watch path too. `WatchLink` calls it through `hub.sessions` plus `hub.changed()`, with no
  ChatHub overload (one upstream file touched, not two). Nicholas can flip the selection.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - No ghost chats: create after the existing checks, delete on a refused send, and `last` written at HEARD
- Options for `last`: write it at send as for existing chats; write it only at REPLY; write it at HEARD.
- Choice: the phone creates only after the empty-text and Buddy-off checks, and `hub.delete`s the chat if `hub.send`
  refuses (still empty then). The watch skips the send-time `remember` on a fresh screen and `Talk` remembers at
  HEARD. A send-time write would make an unreachable phone's never-made chat the watch's default (a watch-side ghost).
  REPLY-only would miss "words sent" (009) when no reply reaches the watch. For existing chats a HEARD write repeats
  the send-time one, so 009 is unchanged.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - No signed-in check for the default agent on the watch path
- Options: refuse with a new phone string when `SetupState.signedIn(Prefs.backend)` is false (like the phone's New
  chat sheet); or don't check (like the phone's voice talk, `Bubble.talk`).
- Choice: don't check. The chat then holds the user's words and the agent's error, which the watch says, so it isn't a
  ghost. The brief's done-when doesn't ask for the check, and it would add a string and a branch. If Helper itself
  can't start, `hub.send` returns an error and the chat is deleted.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - On a fresh talk screen the call button is hidden until the phone has heard the first words
- Options: hide it until a YOU line (from `/buddy/heard`) shows; hide it for the screen's life; leave it (a call
  before any words fails with "That chat isn't on your phone any more").
- Choice: hide it until heard, because a call needs the chat to exist on the phone (`WatchCall.find`). After the
  first words a call in the new chat is as useful as in any other. It costs one line in `draw()`.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The phone accepts a watch-made id only in its own format, `[0-9a-f]{12}`
- Options: accept any string; check the format.
- Choice: check it, because the id becomes a chat file name (`chats/<id>.json`), and `ChatHub.pruneLinux`
  (`ChatHub.java:112`) stops pruning Linux folders if any chat id is outside that format. Anything else gets the
  existing `watch_chat_gone` refusal.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The new-chat flag travels with the conversation: `Talk.fresh` and the notification's intent (review F1)
- Options: (a) `Talk` keeps the flag for its conversation (set by `begin`, cleared at `HEARD`) and puts `"new"` on
  the notification's intent; (b) one process-wide "id made by New chat" static that every talk screen compares with;
  (c) make the notification bring back the existing screen (launch mode or flags) instead of a new one; (d) keep
  pending ids in prefs.
- Choice: (a), because the notification is the only route to a talk screen besides the New chat row for an id the
  phone may not have (the list and `last` hold confirmed ids). The intent also survives recreation and process death,
  where a static (b) would be lost and the restored screen would send without `new` and write a never-made id to
  `last`. (c) changes how every chat's notification opens (003-012). (d) is more state for the same result. Cost: one
  field, one `begin` parameter, one extra on the notification's intent, one line at `HEARD`.
- By: Planner (claude-opus-5-5)
