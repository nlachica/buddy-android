# Buddy workspace

This folder is where Buddy (the phone's chat bubble) builds things for the user. One folder per project.

To create, build, install or update an Android app, read and follow
`.claude/skills/android-app/SKILL.md` in this folder. It has the on-phone toolchain, the
project layout, `build-apk.sh`, and the install steps using the phone tools.

## Running Codex from a shell here (fork)

Codex's own sandbox can't work on this phone (bubblewrap needs namespaces that Android/proot don't give), and runs
with it fail or hang the calling agent. `~/.codex/config.toml` sets `sandbox_mode = "danger-full-access"` and
`~/.local/bin/codex` is a shim that rewrites any `-s/--sandbox/--full-auto` to no sandbox, so `codex exec` and
`codex review` are safe as-is. Wrap headless runs as `timeout 600 codex ...`. After a reinstall or restore, put both
back with `bash /root/.buddy/work/.carry/restore.sh`.

## Feature pipeline (fork)

To build or change a feature in any repo, follow the multi-agent pipeline in
`/root/.buddy/work/pipeline-playbook/AGENTS.md` (skill `feature-pipeline`; runner `pipe`).

## Tools, skills and connectors: Buddy-native first (fork)

When you add infrastructure (a connector, a skill, an API key), put it where Buddy's own screens manage it, so it
shows in Settings and every agent (Claude, Codex, OpenCode) gets it:
- **Remote MCP servers** (https, Bearer or header key): `buddy_settings` name `mcp/<id>`, value the address, and
  `key_file` pointing at a file holding just the key (write it from Bitwarden with `bin/with-secrets`, never print
  it). One approval card. Not `~/.claude.json`: chats run with `--strict-mcp-config`, so servers there are ignored.
  Example: Composio is `mcp/composio`. Local stdio servers and plain CLIs (Bitwarden `bws`, Eight Sleep) can't go
  there; keep them as CLIs under `bin/`.
- **API keys:** `buddy_settings` `service/<id>/key` with `key_file`. Every new key also goes into Bitwarden.
- **Skills:** a folder with `SKILL.md` in `~/.agents/skills/<name>` (Buddy links it into Claude Code). Links in
  there must be relative: Buddy reads the folder from Android, where `/root/...` paths don't resolve. Don't install
  skills only into `~/.claude/skills` or as user-scope Claude plugins; a plugin with hooks stays scoped (`pipe
  --plugin-dir`), with its skills copied here. Avoid names that shadow built-ins (e.g. `code-review`).
