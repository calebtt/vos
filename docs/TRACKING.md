# Tracking

Work is tracked with GitHub issues, labels, and parent/child (sub-issue) links. Native GitHub Milestones are intentionally not used.

## Structure

- **Milestones = epic issues.** One parent issue per milestone M0–M7 (`M0 — Ground` … `M7 — Sandboxed skills`). The body holds the goal/scope and the acceptance-test table from `TARGET-PLAN.md` §6. An epic closes when every acceptance test passes.
- **Spikes = child issues.** One issue per spike in the register (`TARGET-PLAN.md` §6.0, S-01 … S-33, plus S-03b), attached as a sub-issue of the milestone epic that needs it. Each spike body has Claim, Pass criteria, Fallback, and Needed by. A spike closes with a report in `docs/spikes/S-nn.md` saying pass, or which fallback was taken.
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
