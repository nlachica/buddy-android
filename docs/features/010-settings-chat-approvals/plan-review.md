# Plan review: 010-settings-chat-approvals
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [minor] Keep the stored mode visible for notification-rule chats - The listing replaces the mode with `approvals n/a` (plan.md:66, T8 at plan.md:110), although the individual read shows the stored mode plus a refusal (plan.md:62). Editability and applicability differ here: `ChatHub.approvalsFor()` returns the stored mode for any top-level chat (`app/src/main/java/app/buddy/assistant/ChatHub.java:547-550`), and `Guard.runRule()` excludes an active notification-rule run, not every chat associated with a rule (`app/src/main/java/app/buddy/assistant/Guard.java:480-486`). As the plan itself acknowledges at plan.md:128, the mode can therefore affect user-started runs. Hiding it makes the overview less accurate than the individual read and weakens the brief's “lists the mode per chat” coverage. -> Keep the planned write refusal, but list `approvals <mode> (read-only: notification rule's chat)` and adjust T8 to assert the stored mode remains visible. No gate or upstream changes are needed.

VERDICT: APPROVE
