# Decisions: 034-hey-buddy-false-wakes
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-22 - Detection rule and sensitivity levels from measured CI data
- Options: raise the threshold only; threshold + N consecutive hops; add a VAD gate or a transcript check.
- Choice: threshold x N consecutive hops, levels low 0.7x3, medium (default) 0.5x3, high 0.3x2, because the false wakes
  are single-hop spikes (a sound-alike hits 0.97 for one hop) while a real phrase holds >= 0.96 for 4 hops (research.md
  table: 0.5x3 gives 1 false wake in 57 min of noisy speech vs ~300 for 0.12x1). High keeps N = 2 for distorted or
  far voices (the augmented clip reaches only 2 hops). VAD/transcript checks are bigger changes: not in a hotfix.
- By: orchestrator (Claude Opus 5.5)
