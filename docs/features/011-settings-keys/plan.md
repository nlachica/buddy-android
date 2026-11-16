# Plan: 011-settings-keys
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal

Extend 004/005's `buddy_settings` so the agent can **set keys** once Nicholas approves that specific change on one
approval card. Two kinds: an MCP server's key, including adding a server whose discovery needs a key (Composio at
`https://connect.composio.dev/mcp`) with its key in one step, and the key of a service on the API keys screen. The
agent passes the key from a file in Buddy's Linux (for example `~/.config/composio/key`, or a file it filled from
Bitwarden Secrets Manager with `bws`), so Nicholas never types it. The key never shows: the card and every tool reply
show at most its last 4 characters, and the listing stays `key: set|none`.

Done when (brief): (1) adding an MCP server that needs a key, with the key, takes one card, and a denial changes
nothing; (2) setting, replacing or removing an existing MCP server's key or an API key service's key takes one card
each; (3) the key is given by reference to a Linux file, and the card and all tool output show it masked, never whole;
(4) Composio added this way lists its tools in Buddy (manual check on the phone); (5) the agent still can't answer
cards, the F-Droid build is unaffected, and the code fits the repo's style and YAGNI.

Non-goals (brief, plus the ones this plan draws):
- Reading keys out. The listing stays `set|none`, and the card never shows any character of a stored key.
- Changing how approval cards work, or letting the agent answer them.
- Sign-ins (OAuth), the GitHub token, passwords, and a Bitwarden client inside the app.
- F-Droid flavor changes.
- Literal keys in tool arguments (see Design: the file is the only source).
- Adding or removing an API key service, or changing an MCP server's address. The brief asks for keys, and both
  can be added later as rows.
- Fixing upstream's error redaction in `McpServers.rpc`/`relay`, which removes `Bearer <key>` but not the bare token
  (see Risks).

## Design

Everything lives in `SettingsTool.java`, which only the github flavor reaches. The gate (`change()` → `approval()`),
the card string `tools_card_setting`, and `McpServers`, `Providers` and `Secrets` stay as they are. The tool calls
their existing save, discover and list methods the way the two screens do.

### A. How a key is given: `key_file`, one new optional tool argument

`buddy_settings` gets a third optional string argument:
`key_file`: "a file in Buddy's Linux that holds just the key (e.g. ~/.config/composio/key). Buddy reads it itself and
never shows it. Goes with mcp/<id>/key, service/<id>/key, or a new mcp/<id> with its address."

- **Only a file, no literal.** `value` is never a key. On a `/key` name, `value` takes only `""` (remove the key).
  Any other value is refused, and the refusal doesn't repeat it. This follows the brief ("so the agent never echoes
  it into chat"): a literal would sit in the tool call, in the model's context and in the transcript. With a file,
  the agent can pipe `bws` into the file without ever reading the key.
- **Bitwarden** works through the same route. The agent writes the secret's value to a file without printing it,
  then passes the path. Buddy doesn't run `bws` (non-goal: a Bitwarden client in the app; `Linux.exec` starts with
  `env -i`, so `bws` wouldn't find its access token there anyway).
- **Who may use it.** `key_file` is accepted only from a caller that can already read Buddy's Linux: a Buddy chat
  with Linux tools on, or a session that isn't a Buddy chat (`McpServer.CALLER_CHAT` null: a terminal or Remote
  Control session in Buddy's Linux). With Linux tools off an agent has no shell (`linux/buddy-run:347,446`), and
  `key_file` must not become its way to send a one-line Linux file to a server of its choosing (for example
  `~/.claude/.credentials.json`, which is one line). The chat id comes from the MCP address Buddy writes for each
  chat (`McpServer.java:121-129`), not from the model. The rule is one static helper, `linuxCaller(String here,
  Sessions.Session c)` = `here == null || c != null && c.dev`. `call()` reads `CALLER_CHAT` on the request thread
  and looks up the chat on the main thread (the listing's existing pattern).

**Reading the file**: `static String keyFrom(File root, String path)`, Context-free, root = `Linux.root(svc)`. Inside
Linux, `/` is that root and HOME is `/root` (`Linux.java:76-90`).
1. Trim the path. It must be non-blank, one line, at most 1,000 characters, and start with `/` or `~/` (`~` becomes
   `/root`). Relative paths are refused because there is no working directory to resolve them against.
2. `real = new File(root, path).toPath().toRealPath()`. It must be inside `root.toPath().toRealPath()` plus a
   separator (the `skillFile` check, `SettingsTool.java:907`). That refuses an absolute or `..` link that resolves
   outside Linux on the Android side: Buddy's own `shared_prefs`, the host's `/etc`.
3. `Files.isRegularFile(real, NOFOLLOW_LINKS)` (no directory and no FIFO, which would block), and at most 4 KB.
4. Read through `Files.newByteChannel(real, READ, NOFOLLOW_LINKS)` so a last-moment swap of the final name for a link
   isn't followed. Decode as UTF-8 and trim, which drops the newline `echo` and `bws | jq` leave.
5. The content must be non-empty and contain no control characters (the existing `CONTROL` pattern, so one line).
   bws's raw JSON is refused with: "the file must hold just the key, one line (for a Bitwarden secret, its value)".
6. **No message echoes the path or any content.** An agent that passes the key itself as `key_file` gets "key_file
   must be a path in Buddy's Linux starting with / or ~/" and nothing more. The card shows the path only once the
   read succeeds.

**Showing a key**: `static String mask(String key)` = `"…" + last 4 + " "` only when the key has at least 16
characters (so at most a quarter shows), then `"(" + length + " characters)"`. Example: `…ab12 (40 characters)`. A
stored key shows only as `set` or `none`, never by its characters. **Redaction**: `static String redact(String text,
String key)` replaces the key with `…`, and also the part after a leading `Bearer ` or `Name: `, so a server that
quotes back only the bare token is covered too. Every network error on the key path goes through it before it reaches
a reply.

### B. `KeySetting`: one Setting kind for a saved key (two uses: MCP server, API key service)

`abstract static class KeySetting extends Setting` takes `(name, File root, String file)`, where `file` null means
remove. Its four hooks:
- `String target()`: the non-secret record the key belongs to, including its address(es), or null when that record
  is gone.
- `String saved()`: the saved key, or null. The stored key never leaves this method.
- `void verify(String key)`: after the card, off the main thread. It checks the key with the server and keeps the
  result for `store`. The default does nothing.
- `String store(String key)`: on the main thread, under `lock()`. It writes the key (`""` removes it) and returns a
  note.

The class fixes the rest. Each point is tied to the gate in `change()` (`SettingsTool.java:1091`):
- `get()`: null when `target()` is null; otherwise `[target, sha256(saved)]` as a JSONArray, with `""` for no key.
  The snapshot holds a digest, never the key. It also holds the address, so an address edited in Settings while the
  card is open makes the write stale ("changed while the card was open"), and the key never goes somewhere the
  snapshot didn't show.
- `view(snapshot)`: `(none)` when the record is gone, otherwise `none` or `set`.
- `check(value)`, on the tool thread:
  - a non-blank value is refused with "<name> takes its key from key_file, never in value; value \"\" removes it",
    without the value;
  - a missing record is refused ("no MCP server x" or "no such service x");
  - removal with no key saved is refused with "<name>: there is no key to remove";
  - otherwise `key = keyFrom(root, file)` goes into a private field, and `check` returns
    `mask(key) + ", from " + file`.

  So `v` in `change()` is never the secret. A set is never answered "Already": comparing against the stored key
  would let an agent test a guess without a card.
- `card()`: `{view(old), v.isEmpty() ? "none (the key is removed)" : v}`. It uses the existing card string.
- `prepare(v)`: when setting a key, `verify(key)`. Any exception is rethrown as `new Exception(redact(message,
  key))`.
- `set(v)`: `store(v.isEmpty() ? "" : key)`.
- `read()`: `name = set|none  (a key from key_file, or "" to remove it; never shown)`.

**`mcpKey(svc, id, root, file)`**, the name `mcp/<id>/key`, mirrors the MCP screen's save
(`McpServersActivity.java:178-234`):
- `target` = the server's url (`McpServers.find`);
- `saved` = `Secrets.get(svc, McpServers.secretName(id))`;
- `verify` = `McpServers.discover(currentUrl, McpServers.header(key))`, which keeps the tools and throws if the server
  is gone;
- `store` = removal → `McpServers.save(svc, s, "")`, which removes the secret and leaves the tools as they are, with
  no connection made, the way 004's on/off is; set → `s.tools = found; McpServers.save(svc, s, key)`, note
  `" It lists N tools."`;
- `lock()` = `McpServers.class`, which every `McpServers` writer holds.

The relay reads the key on each request (`McpServers.java:318`), so the new key applies at once.

**`serviceKey(svc, id, root, file)`**, the name `service/<id>/key`, mirrors the API keys screen's save
(`ApiKeysActivity.java:300-372`):
- `target` = the service's two addresses (`Providers.find`);
- `saved` = `Secrets.get(svc, Providers.secretName(id))`;
- `verify` = `Providers.list(key, p.anthropic, p.openai)`, which keeps the models (the screen checks a key the same
  way);
- `store` = set → `p.models = models; Providers.save(svc, p, key)`; removal → `Providers.save(svc, p, "")`. That is
  the screen's own state for a service without a key: the relay still finds the service (`Relay.java:273-285`), and a
  remote service then refuses calls until a key is set;
- `lock()` = `Providers.class`.

The default-agent fill-in and `refreshAgents` that the screen runs don't run here: a key change doesn't change which
services exist, and nothing they read depends on the key.

### C. A new MCP server with its key, in one step

`mcp(svc, id, existing, keySet)` gains `(File root, String keyFile)`:
- **An existing id with `key_file`** is refused: "mcp/x exists: set its key with mcp/x/key". 004's rule still holds:
  an address can't change on an existing id.
- **A new id**: after the free-name and address checks, `key = keyFrom(root, keyFile)`.
  - Card: `add the server at <url> with the key …ab12 (40 characters), from ~/.config/composio/key`. Without a key,
    it still reads `(no key)` as in 004.
  - `prepare`: `discover(url, key == null ? null : McpServers.header(key))`, errors redacted.
  - `set`: `McpServers.save(svc, s, key == null ? "" : key)`, note `" It lists N tools."`.

  `McpServers.header` already handles a key written as `X-Api-Key: …` for servers that want another header
  (`McpServers.java:166-172`).
- **Denial**: `approval()` returns before `prepare`. Nothing connects and nothing is saved.

### D. Wiring and words

- `find(svc, name)` becomes `find(svc, name, file)` (the old form passes null, for reads and the listing).
  - `mcp/<id>/key`, for an existing server, → `mcpKey`.
  - `service/<id>/key` → `serviceKey`. `key` joins the `(name|models)` alternation at `SettingsTool.java:1214`.
  - `mcp/<id>` passes `file` on to `mcp()`.
- `call()`:
  - `key_file` with no name is refused.
  - With neither `value` nor `key_file`, it is a read, as now.
  - `key_file` with a name that isn't `mcp/<id>/key`, `service/<id>/key` or `mcp/<id>` is refused ("key_file goes
    with …").
  - `key_file` from a non-Linux caller is refused ("key_file needs Linux tools in this chat").
  - Otherwise `change(find(...), value, …)` runs as now. With `key_file` and no `value`, the value is `""`.
- Tool description (`list()`, `SettingsTool.java:1143`): replace "Keys, tokens and server addresses are never shown or
  changed here." with:

  > Keys are never shown. To set one (mcp/<id>/key, service/<id>/key, or a new mcp/<id> with its address), put just
  > the key in a file in Buddy's Linux, without printing it (from Bitwarden: the secret's value), and pass its path
  > as key_file. On a /key name, value "" removes the key. Needs Linux tools in this chat.

  Add the `key_file` property.
- Listing headings: MCP becomes "(mcp/<id>; a new id with an address adds one, key_file adds its key; each also has
  mcp/<id>/key)". API keys becomes "each also has service/<id>/name, /models and /key". The lines themselves don't
  change.
- Docs:
  - `docs/GUIDE.md:204`: the fork's parenthesis gains "including MCP server and API keys, read from a file in Buddy's
    Linux and never shown".
  - `AGENTS.md:25`: the same half-sentence.

### Alternatives (one line each)
- Literal key in `value`: the brief wants it never echoed into chat, and 004's cards show values whole.
- `value` = the file path on `/key` names: the one-step MCP add still needs a URL and a key, so it needs a second
  argument anyway.
- The key on `mcp/<id>` itself (`key_file` with no `/key` name): it mixes on/off/delete and the key in one setting,
  and removal has no clean value.
- `bws:<id>` run by Buddy: a Bitwarden client in the app (non-goal), and there is no token in `env -i`.
- The raw key in the snapshot: the digest compares just as well, and a slip in `view` can't leak it.
- Showing the stored key's last 4 on the card: the brief keeps listing masked, and `set`/`none` is enough to
  compare against.
- Saving without `verify`: the screens verify, so a wrong key would sit unnoticed, with stale tools and models.

## Steps

1. **Key helpers and tests.** `SettingsTool.java`: `keyFrom`, `mask`, `redact`, `digest` (SHA-256 hex, `""` for
   null/empty) and `linuxCaller`. New `app/src/test/java/app/buddy/assistant/SettingsKeysTest.java`: Test plan 3a-3c.
2. **`KeySetting` and tests.** `SettingsTool.java`: the abstract class from Design B. `SettingsKeysTest`: an
   in-memory subclass (a map-backed record, key and store) for Test plan 1-3 at the gate level.
3. **MCP and service keys, wiring.** `SettingsTool.java`:
   - `mcpKey` and `serviceKey`;
   - `mcp()` with `root` and `keyFile` (Design C);
   - `find(svc, name, file)` and the `call()` rules (Design D);
   - the description, the `key_file` property and the listing headings.

   `SettingsFamiliesTest.mcpServersNeverShowTheirAddressOrKey` is updated to the new `mcp(...)` signature. New tests
   cover a new server with a key: card, deny, refusals (Test plan 1).
4. **Docs.** `docs/GUIDE.md:204`, `AGENTS.md:25`.

The release (BuildInfo `2.3.10-fork.2`, VERSION_CODE = max(fork 212, upstream) + 1 at that time, the tag, and
`update_buddy`) is the orchestrator's after the merge, with Nicholas's OK ("Updating Buddy on the phone").

## Test plan

All unit tests are JVM tests in `SettingsKeysTest` (temp folders, real symlinks), plus the updated
`SettingsFamiliesTest`. The CI command is `testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease
:wear:assembleRelease` (`build.yml:63`). Device rows need the fork release on the phone (playbook learning
"phone-side release for device check"): verify after shipping, fix forward.

| Done-when item | Proof |
|---|---|
| 1. Add an MCP server needing a key, with the key, after one card; denial changes nothing | **Unit**: `mcp(null, "composio", null, false, root, "/root/.config/composio/key")`, where the file holds a 40-character key. `check("https://connect.composio.dev/mcp")` returns the URL. The card's New has the URL, `…` + the last 4, `40 characters` and the path, and not the key. `change(...)` with a denying asker (`DENIED…`, and each self-approval string from `ApprovalGateTest`) returns `Not changed.` and asks once, with no `prepare` (no network) and no save. An existing id with `key_file` is refused before the file is read. A new id with a bad address is refused. **Device**: see item 4, plus one denied add: the server is not in Settings → MCP servers. |
| 2. Set/replace/remove an existing MCP key and an API service key, one card each | **Unit** (in-memory `KeySetting`): set when none (card `Now: none`, `New: …wxyz (N characters), from …`), approved → store called once with the file's key, reply `Changed …: none → …`. Replace (Now `set`). Remove (`value ""`, no key_file) → store gets `""`, card `none (the key is removed)`. Remove with no key → `Not changed: … no key to remove` and no card. Denied → store never called. **Stale**: the asker changes the record's address, swaps the saved key, or removes the record before `APPROVED…` → "changed while the card was open" (or "now: (none)"), store never called. `verify` throws → `Not changed: …`, store never called. **Device**: on Composio, replace its key from the file → card → approve → still lists tools. Remove → card → deny → `key: set`. Approve → `key: none`, and the MCP screen's key field shows the empty hint. Set again. `service/<id>/key` set → deny → unchanged, and approve if a saved service exists; otherwise this row is unit-only, recorded in verify.md. |
| 3. Key by reference to a Linux file; card and all output masked | **Unit 3a, `keyFrom`** (temp root): `/root/k` and `~/k` read, trailing newline trimmed. Refused, and the message never contains the path's secret part or the content: a relative path, a key passed as the path (`sk-abc…`), a missing file, a directory, an absolute link to a file outside the root holding `TOKEN-123`, a `../../outside` link, a two-line file, an empty file, a 5 KB file. **3b, `mask`/`redact`**: a 40-character key → `…` + last 4 + `(40 characters)`; a 10-character key → `(10 characters)` only. `redact` removes the key, `Bearer <key>`'s bare token and `X-Api-Key: v`'s value from a text. **3c, `linuxCaller`**: null caller → yes; a chat with `dev` on → yes; off → no; unknown id → no. **3d, no-leak sweep** (in-memory `KeySetting` with a 40-character key): every string the tool can return contains no full key and nothing of it beyond the last 4 (`read`, `line`, `view`, card, `Changed`, stale, each refusal, and a `verify` error that quotes the key and the bare token back). A literal key in `value` is refused, its text not in the reply, and the file is never read. |
| 4. Composio added this way lists its tools | **Device, manual**, after the release: in a chat with Linux tools, the agent writes the Composio key into `~/.config/composio/key` without printing it (from Bitwarden with `bws` if that's where it is). It calls `buddy_settings name=mcp/composio value=https://connect.composio.dev/mcp key_file=~/.config/composio/key`. One card shows the URL and the masked key, and the agent never sees the key. Approve → the reply says `It lists N tools`, Settings → MCP servers shows composio with N tools, and a new chat can call one `mcp__composio__…` tool. Also: a chat with Linux tools off passing `key_file` → refused. |
| 5. Cards unanswerable; F-Droid unaffected; style/YAGNI | `ApprovalGateTest` and the existing settings tests are unchanged and green. The gate code (`approval`, `change`) is untouched (diff). `git diff main --stat` shows only `SettingsTool.java`, tests and docs: no change to `McpServers`, `Providers`, `Secrets` or `Tools`, so nothing new is reachable in the F-Droid flavor. CI is green for both flavors, and "F-Droid APK is reproducible" passes. Code review against the repo's style. |

## Risks

- **Prompt injection adds a server and sends it a key.** An injected agent with Linux tools could add an attacker's
  server with a Linux file as its key. It can already send such a file anywhere with `curl`, so the card (URL, path
  and masked key, all shown) is the user's check, and the Linux-tools rule keeps agents without a shell out.
- **Link race on a directory in the path.** Between `toRealPath` and the open, an agent with Linux tools could swap
  a directory for a link that leads outside Linux. The final name isn't followed (`NOFOLLOW_LINKS`), and only one
  line of at most 4 KB is taken, which rules out Buddy's XML and JSON stores. The card names the path, and the key
  goes only to a server the card shows. Accepted, like 004's skill-link race.
- **Composio specifics are unverified** (research skipped). `discover` doesn't follow redirects, and Composio may
  want another header or OAuth only. The reply carries the server's own (redacted) words. A key that has to go in
  `x-api-key` works by writing the file as `x-api-key: …`. If Composio needs something `discover` can't do, that is
  its own fix, recorded in verify.md.
- **Upstream redaction gap, not fixed here.** `McpServers.rpc` and `relay` redact `header[1]` (`Bearer <key>`), not
  the bare token, so a server error quoting the bare token reaches the screen or the relayed agent. That predates
  this feature, and this feature's own replies go through `redact`. It could be reported upstream.
- **The key file stays in Linux**, readable by any agent with Linux tools. That is where Nicholas keeps it, and Buddy
  doesn't delete his files.
- **A wrong key fails `verify`**, and nothing is saved. A local API service that is offline can't take a key until it
  is back, the same as on the screen.
- **Device rows need a release** (playbook learning). Unit tests and CI cover items 1-3 and 5 before the merge. Items
  2 (device part) and 4 come after `update_buddy`, and a bug there is fixed forward with `-fork.3`.

## Open questions

None blocking.

For Nicholas (default in brackets): adding a *new* API key service with its key (e.g. OpenRouter from Bitwarden) is
not in the done-when [not in this feature; it can follow as a `service/new` row using the screen's presets].
