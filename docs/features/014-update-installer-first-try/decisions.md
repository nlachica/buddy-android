# Decisions: 014-update-installer-first-try
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - Design for an unconfirmed cause: check the screen, retry once, then a tap-to-install notification
- Options: always hand off by notification; download first and start the installer from the card tap; full-screen
  intent; PackageInstaller session API; start as today, then check the real screen, retry once, then notify (chosen).
- Choice: check, retry, notify. AOSP (android14/15/16-release) binds accessibility services with
  `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` and allows their starts by token, so the brief's BAL guess is doubtful. The
  only firm fact is "the second try works". The chosen design handles a first-attempt cause (by the retry) and a BAL
  cause (by the user's notification tap), and it keeps today's zero-tap path when the start works. The other options
  add a tap to every update, change the flow, need a permission Android 14+ revokes, or have the same launch problem.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The check reads the real screen with wait_for_install's predicate, 8 s per attempt
- Options: `Tools.windows()` (the calling chat's display); `svc.getWindows()` (the default display); a new predicate.
- Choice: `installerOnScreen(svc.getWindows())`, the predicate taking a window list. The installer always lands on the
  default display, while a background-mode chat's `windows()` reads its own virtual display. The predicate is the one
  `wait_for_install` already relies on. 8 s per attempt: the APK is about 7 MB (v2.3.10-fork.5) and stages in under a
  second, so the installer normally shows in 1-3 s.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Fallback notification reuses the "updates" channel and ID 3, English-only strings
- Options: a new high-importance channel (heads-up); the existing default-importance `updates` channel with ID 3.
- Choice: existing channel and ID. It replaces the "update available" notice, which is about the same subject, and
  `cancel()` keeps meaning "the update notification". The agent tells the user to tap it, so a heads-up isn't needed.
  A new channel would mean a new user-visible setting and its own string. Strings are English-only, the fork's
  convention since 001/003/005/008.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The result says when the installer came up on the second try
- Options: say nothing; `Log.i` only; a short note in the tool result (`OPENED_RETRY`).
- Choice: a note in the result. It is the only on-device evidence of the cause that needs no logcat access (Phone
  commands may be off), and it costs one clause.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - update_buddy counts in installWaits while it downloads and checks
- Options: leave it out; count it, as BuddyService's comment on `installWaits` already says ("wait_for_install,
  update_buddy").
- Choice: count it. With the new check window, a user touching the screen while the installer is late would
  otherwise auto-pause the chat. It's 3 lines and matches the field's documented intent.
- By: Planner (claude-opus-5-5)
