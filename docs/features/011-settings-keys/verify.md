---
type: verify
feature: 011-settings-keys
---
# Verify: 011-settings-keys
- [x] New MCP server with a key on one card; denial changes nothing: `aNewServerWithAKeyShowsItMaskedOnOneCardAndADenialChangesNothing`, `aDeniedKeyChangesAndSendsNothing`.
- [x] Set/replace/remove MCP and service keys, one card each: `setAsksOneCardMasksTheKeyAndStoresIt`, `removeAsksOneCardAndReadsNoFile`.
- [x] Key by file reference, never echoed; masked to last 4 at most: `keyFrom*`, `mask*`, `redact*`, `noStringTheToolReturnsHoldsMoreThanTheLast4`, `aLiteralKeyInValueIsRefusedUnreadAndUnrepeated`.
- [ ] Composio lists its tools in Buddy: manual check on the phone after installing the release.
- [x] Agent can't answer cards (unchanged confirm path; `onlyCallersWhoCanReadLinuxMayPassAKeyFile`); F-Droid flavor untouched; CI green after merging main (run 43788856814: helper-tests, apk).
