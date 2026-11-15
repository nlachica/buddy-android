# Plan review: 005-settings-coverage
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] The service-name setting is lost inside the secret-editor exclusion - The brief requires every non-excluded Settings control, but `plan.md:144` excludes all service editing because the editor holds a key, and `research.md:38` bundles the editor rather than inventorying its fields. The name is a separate non-secret field (`app/src/main/java/app/buddy/assistant/ApiKeysActivity.java:209,220,301,344`); the screen even avoids fetching models for an ordinary rename (`:327-331`). The plan already correctly rejects the same blanket exclusion for shown models (`plan.md:139-141`). -> Inventory the name separately and add a narrow `service/<id>/name` change with old/new text and stale-write coverage, preserving addresses, keys and other fields. Keep the secret fields and credential-changing flows excluded.

F2 [major] The phone verification matrix omits new families - `plan.md:212` substitutes `default_agent` OR `default_model/<agent>`, and never exercises `reviewer_model/<h>` or `service/<id>/models`. These have distinct parsing/storage paths in steps 2 and 4, so testing the scalar selector or service on/off cannot prove their approved and denied changes work. The brief's last done-when item requires both outcomes per new family. The same row allows prerequisites to be merely "noted", while the risks only explicitly retain PARTIAL when a release cannot happen (`:231-232`). -> Enumerate the family coverage explicitly, including both model-setting families and the service model list (and the service name from F1). Require one actual approved change and one denied change for each; mark missing prerequisite-dependent evidence pending/PARTIAL rather than satisfied. A request that returns "Already" is not approval evidence.

F3 [major] Service deletion during approval has no specified stale-write protection - The new service rows are described as getters/setters over `p`, with an existence check (`plan.md:75-76`), but no requirement to resolve the live service again for each snapshot and commit. `SettingsTool.change` validates only before the card and later compares `s.get()` (`app/src/main/java/app/buddy/assistant/SettingsTool.java:617-618,628-635`). A captured `Providers.P` survives removal; `Providers.save` explicitly re-adds it if its id is absent (`Providers.java:180-183`), while `setOff` mutates the supplied object even if it is no longer in the store (`:151-156`). Thus a naive implementation of the planned callbacks can resurrect a removed service when changing shown models, or report a successful on/off change to a detached object. The planned stale tests only mutate an in-memory value (`plan.md:211`), leaving this wiring untested. -> Specify live id lookup in the production getters and setters, return an absent snapshot after deletion, and refuse writes to missing/replaced services without recreating them. Add deletion-during-card cases for both service routes using a mutable lookup seam that exercises the actual lookup behavior; apply the same rule to F1's name route. The shared gate can remain unchanged.

F4 [minor] Button-rule removal must work after its app is uninstalled - `plan.md:100` requires an installed package for the button family without exempting removal. The screen lists stored rules and deletes them without an installation check (`app/src/main/java/app/buddy/assistant/AppPermissionsActivity.java:275-282`), and `Guard.rules` retains their package strings (`Guard.java:135-143`). The proposed check therefore leaves a listed permission rule that the tool cannot remove, although Settings can. -> Require installation when adding a new app-scoped rule, but permit reading and removing an existing rule for an absent package; use its stored app label or package on the card. Add a regression case for removing an orphaned rule.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - added `service/<id>/name` (Agents and models table). It takes one trimmed line and refuses a blank
name (the screen would fill one in from the address, and the tool never reads addresses). It sets `p.name` on the
live service, then calls `Providers.save(svc, p, null)`, the editor's own save with the key kept. Addresses, key and
model list are untouched. The editor's model fetch and default-agent fill-in don't run, because a rename changes
nothing they read (`ApiKeysActivity:327-331`). Step 7 splits research.md's API keys row into one row per control:
name covered; key, addresses, add and remove excluded (1); Check now is not a setting. Unit tests cover shape, card,
stale write and no secret; phone matrix row 9.

F2: accepted - Test plan item 4 is now a 12-row matrix: the five Settings pages' scalar groups, plus one row each for
`default_model/<agent>`, `reviewer_model/<h>`, `service/<id>`, `service/<id>/name`, `service/<id>/models`,
`button/…` and `chat/<id>/allowed/<pkg>`. Each row needs one approved and one denied change on a real card.
"Already" and refusals before the card don't count. A row whose prerequisite is missing stays pending, so the
verdict is PARTIAL (also in Risks).

F3: accepted - all three service rows come from one factory, `service(id, part, find, store)`:
- `get`, `check` and `set` each look the service up through `find` (in production `Providers::find`).
- `get` returns null once the service is gone. Otherwise the snapshot is its non-secret record minus the model list,
  which a background refresh rewrites, so a removed or same-id re-added service fails the gate's compare. This is
  004's `mcp/<id>` pattern, and `view` shows only the part.
- `lock()` is `Providers.class`.
- `set` throws IllegalStateException and never calls `store` when the service is missing, so `save` can't add it
  back.

The gate stays unchanged. JVM tests use a map-backed `find`. For each part: the service is removed or replaced while
the card is open, `store` is never called and the map isn't re-populated. Separately, `set` with the service gone is
refused, and no reply contains an address.

F4: accepted - an installed app is needed only to put an app-scoped rule that is on neither list onto one. A rule
already on a list can be read, moved or removed (`""` is always accepted), as the screen's chips allow
(`AppPermissionsActivity:275-282`). The card names the installed label, else the stored `app`, else the package. The
factory gains an `installed` flag so JVM tests cover orphan removal and the refused new rule.

## Round 2 - Plan Reviewer (gpt-6-astra)

No new findings. Round 1 F1–F4 are resolved in the revised plan: the service name has its own row and inventory entry; the phone matrix requires actual approved and denied changes for every new family, with missing evidence remaining PARTIAL; service rows use live lookup, absent snapshots and missing-service write refusal; and existing button rules remain removable after app uninstall.

Checked the proposed wiring against `SettingsTool.java:612-641`, `Providers.java:134-196`, `ApiKeysActivity.java:343-349,433-438`, and `AppPermissionsActivity.java:275-282`. The service snapshot and lock fit the existing gate without changing it. The planned validation, card, deletion/replacement and no-secret tests cover the revised paths. No blocker or major finding remains open.

VERDICT: APPROVE
