# Tracking

## Source of truth

**GitHub `calebtt/vos` is the source of truth** for the plan (`docs/`), issues, milestones, and decisions. Every other copy — the desktop clone (`~/vos`), the copy on the data disk (`vos/`), and agent box copies under `/workspace/vos` — is a cache. Edit by committing to `main` (git push or the GitHub API), then pull the caches. If a cache and GitHub disagree, GitHub wins; never hand-merge a stale cache back over `main`.

Work is tracked with GitHub issues, labels, parent/child (sub-issue) links, and **native GitHub Milestones M0–M7** (every issue is attached to one, so the Milestone filter works). The `milestone:Mn` labels are kept as secondary tags.

## Structure

- **Milestones = epic issues.** One parent issue per milestone M0–M7 (`M0 — Ground` … `M7 — Sandboxed skills`). The body holds the goal/scope and the acceptance-test table from `TARGET-PLAN.md` §6. An epic closes when every acceptance test passes.
- **Spikes = child issues.** One issue per spike in the register (`TARGET-PLAN.md` §6.0, S-01 … S-44, plus S-03b, S-UI-TREE and S-UI-LAT), attached as a sub-issue of the milestone epic that needs it; post-1.0-lane and backlog spikes (S-39, S-UI-TREE, S-UI-LAT, S-44) attach to a "Post-1.0 lane" or "Backlog" issue instead. **Open item (2026-10-18):** spikes S-40–S-44 and the tests added on 2026-10-17/18 (decisions 30–37, M5-QUERY-01, M3-UI-05a/b, M3-ESC-01b) have no issues yet; open them when the plan branch merges. Each spike body has Claim, Pass criteria, Fallback, and Needed by. A spike closes with a report in `docs/spikes/S-nn.md` saying pass, or which fallback was taken.
- **Work items** are opened as further child issues of an epic once the spike that gates them has landed.
- **Day 0** has its own issue (`day-0`) for the pre-M0 cleanup and ordering (§8).
- Big fork choices get a short ADR entry in `docs/DECISIONS.md`.

## Labels

| Label | Meaning |
|---|---|
| `spike` | A device claim that must be proven before anything depends on it |
| `gate` | Blocks a milestone decision (e.g. the M0 platform/audio decisions, M4 exit) |
| `day-0` | Pre-M0 cleanup and ordering |
| `audio` | AudioPlane, mic, routing, duplex |
| `platform` | Image, OTA, signing, Graphene/Android platform behavior |
| `security` | Keys, auth, egress, trusted path |
| `milestone:M0` … `milestone:M7` | Which milestone the issue belongs to |

Source of truth for claims and pass criteria is `docs/TARGET-PLAN.md`; if an issue and the plan disagree, fix the plan first, then the issue.
