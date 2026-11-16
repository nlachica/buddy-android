# Decisions: 011-settings-keys
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - A key comes only from a file in Buddy's Linux (`key_file`), never as a literal
- Options: a literal key in `value`; `value` = the file's path on `/key` names; a JSON value {url, key_file}; `bws:<id>`
  that Buddy runs itself; a separate `key_file` argument.
- Choice: a new optional `key_file` argument. On a `/key` name, `value` only takes `""` (remove the key), and any other
  value is refused without being repeated. A literal would sit in the tool call, the model's context and the
  transcript, and the brief wants the key never echoed into chat. 004's cards also show values whole. The one-step
  MCP add needs a URL and a key, so a second argument is needed anyway. Bitwarden goes through the same file: the
  agent writes the secret's value there without printing it. Buddy running `bws` would be a Bitwarden client in the
  app (non-goal), and `Linux.exec`'s `env -i` has no access token.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Key settings are sub-names: `mcp/<id>/key`, `service/<id>/key`, plus `mcp/<new id>` + key_file
- Options: put the key on `mcp/<id>` / `service/<id>` itself; separate `/key` names.
- Choice: `/key` names, like 005's `service/<id>/name` and `/models`. One name changes one thing, and removal is
  simply `value ""`. A new `mcp/<id>` also takes `key_file`, so Composio is added with its key on one card. An existing
  `mcp/<id>` with `key_file` is refused and pointed at `mcp/<id>/key` (004's rule that an address never changes on an
  existing id still holds).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Reading the key file: confined to Linux.root, one line, nothing echoed
- Options: read any path; read `new File(root, path)` as given; resolve the real path, check it is inside the root,
  then read.
- Choice: `keyFrom(root, path)`:
  - the path starts with `/` or `~/`;
  - its real path is inside `Linux.root`'s real path (the `skillFile` check);
  - it is a regular file of at most 4 KB, opened without following a final link;
  - the trimmed content is one line with no control characters.

  No message echoes the path or the content, in case an agent passed the key as the path. An agent can create links
  in Linux that resolve, on the Android side, to Buddy's private files, so confinement is required. The remaining
  directory-swap race is accepted, as 004's skill race was.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - `key_file` only from callers that can already read Buddy's Linux
- Options: any caller; only a chat with Linux tools on; a chat with Linux tools on, or a non-chat session.
- Choice: `linuxCaller(here, chat)` = `CALLER_CHAT` null (a terminal or Remote Control session in Buddy's Linux) or
  the calling chat has Linux tools on. With Linux tools off an agent has no shell (`buddy-run`). Without this rule,
  `key_file` would let such an agent send a one-line Linux file (e.g. `~/.claude/.credentials.json`) to a server it
  adds, behind a card the user might approve. The chat id comes from the MCP address Buddy writes per chat, not from
  the model.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Masking: at most the new key's last 4 plus its length; a stored key is only "set"/"none"
- Options: show the stored key's last 4 on the card; show nothing but "set"; show the new key's last 4 and its length.
- Choice: a new key shows as `…ab12 (40 characters)`, with the last 4 only when it has 16 or more characters, followed
  by the path it came from. A stored key is never shown by its characters, only `set` or `none`. A set never answers
  "Already" (no oracle for guessing a stored key without a card). The brief keeps the listing masked, and length plus
  last 4 are enough for Nicholas to spot an empty or wrong file. Network errors on the key path go through `redact`
  (the key, and its bare token after `Bearer ` or `Name: `).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - One `KeySetting` kind: digest snapshot with the address, verify after the card, then save
- Options: per-kind ad-hoc Settings; extend 005's `service()` with part "key"; an abstract `KeySetting` with hooks
  (target, saved, verify, store).
- Choice: `KeySetting`, which has two uses (`mcpKey`, `serviceKey`) and an in-memory test subclass.
  - Its snapshot is `[target with address, sha256(saved key)]`. An address or key changed while the card is open makes
    the write stale, and the stored key never leaves `saved()`.
  - `verify` mirrors each screen's save: MCP `discover` with the new key, which keeps the tools; API `Providers.list`,
    which keeps the models. It runs after the card, so a wrong key saves nothing.
  - API key removal stores `""`, the screen's own state for a service without a key, which the relay still finds.
  - `McpServers`, `Providers`, `Secrets` and `Tools` stay unchanged, so the diff stays in fork-only code.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Out of scope: adding/removing API key services, changing MCP addresses, upstream redaction gap
- Options: add `service/new` with presets and a key; fix `McpServers.rpc`/`relay` redaction of the bare token.
- Choice: neither. The brief's done-when asks for keys of existing services and for one-step MCP add with a key.
  The redaction gap predates this feature and sits in upstream code, while this feature's own replies are redacted.
  Both can follow on their own.
- By: Planner (claude-opus-5-5)
