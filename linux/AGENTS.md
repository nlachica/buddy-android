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
