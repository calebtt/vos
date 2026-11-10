# Tracking

## Source of truth

**GitHub `calebtt/vos` is the source of truth** for the plan (`docs/`), issues, milestones, and decisions. Every other copy — the desktop clone (`~/vos`), the copy on the data disk (`vos/`), and agent box copies under `/workspace/vos` — is a cache. Edit by committing to `main` (git push or the GitHub API), then pull the caches. If a cache and GitHub disagree, GitHub wins; never hand-merge a stale cache back over `main`.

Work is tracked with GitHub issues, labels, parent/child (sub-issue) links, and **native GitHub Milestones M0–M7** (every issue is attached to one, so the Milestone filter works). The `milestone:Mn` labels are kept as secondary tags.

## Structure

- **Milestones = epic issues.** One parent issue per milestone M0–M7 (`M0 — Ground` … `M7 — Sandboxed skills`). The body holds the goal/scope and the acceptance-test table from `TARGET-PLAN.md` §6. An epic closes when every acceptance test passes.
- **Spikes = child issues.** One issue per spike in the register (`TARGET-PLAN.md` §6.0, S-01 … S-44, plus S-03b, S-UI-TREE and S-UI-LAT), attached as a sub-issue of the milestone epic that needs it; post-1.0-lane and backlog spikes (S-39, S-UI-TREE, S-UI-LAT, S-44) attach to a "Post-1.0 lane" or "Backlog" issue instead. **Open item of 2026-10-18, corrected 2026-11-10:** spikes S-40–S-44 have issues: S-40 #113, S-41 #114, S-42 #115, S-43 #116, S-44 #117 (the spike register, `TARGET-PLAN.md` §6.0). The tests added on 2026-10-17/18 (decisions 30–37, M5-QUERY-01, M3-UI-05a/b, M3-ESC-01b) carry issue numbers in the plan's test tables too (M5-QUERY-01 #110, M3-UI-05a #103, M3-UI-05b #104, M3-ESC-01b #89). Each spike body has Claim, Pass criteria, Fallback, and Needed by. A spike closes with a report in `docs/spikes/S-nn.md` saying pass, or which fallback was taken.
- **Work items** are opened as further child issues of an epic once the spike that gates them has landed.
- **Day 0** has its own issue (`day-0`) for the pre-M0 cleanup and ordering (§8).
- Big fork choices get a short ADR entry in `docs/DECISIONS.md`.

## Landed ahead of its milestone (2026-11-10, #338)

- **Audit hash chain (M2 scope "Audit v2", #154).** Landed 2026-11-05. `AuditChain` and `JsonlAudit` in `ipc` chain every audit line, in the actuator and in the session, to the line before it (its SHA-256, in `prev`). `AuditVerify` is the host verifier, and `eval/m2/audit-01.sh` is the runner. **M2-AUDIT-01 (#154) stays an M2 test:** its `--device` run passed on the emulator on 2026-11-10 (`eval/README.md`), and its Phone B run is pending. Not landed: the rest of that M2 scope line ("what did you do today", the undo journal), and the parts of M1's audit v1 the code still names as to come (rotation, the owner view, export).
- **High-risk redactor (M2 scope, part of #276).** Landed 2026-11-05 in the session: `HighRiskRedactor`, and `Conversation.redactHighRisk` for the shared conversation and its summary. Nothing calls it at task end yet. The taint set, the actuator's hand-off of the released strings and the task-record half are M2 work, and M2-CTX-01 (#277) stays in M2.

## M1 tests with legs that need later milestones (2026-11-10, #338)

Five rows of the M1 acceptance table (`TARGET-PLAN.md` §6, M1) cannot pass in M1 as written. Each has legs M1 can test and legs that need work the plan puts in a later milestone, or roles that are stubs in M1. Splitting these rows into an M1 part and a later part is the owner's decision and is not made here. No test text and no date is changed.

| Test | Leg that cannot run in M1 | What the plan says it needs |
|---|---|---|
| M1-CHAT-01 (#69) | "repeat in Quiet; try in Paused" | Agent modes Full / Quiet / Paused are M2 scope (decision 20, M2-MODE-01) |
| M1-TEAM-03 (#72) | The Paused veto, and the veto for an irreversible class without owner presence; also "Planner and Vision propose conflicting next steps" | Agent modes and presence (§5.7) are M2 scope; Planner and Vision are stubs in M1 (§3.7 "v0 shape", §9 Q21) |
| M1-IND-01 (#118) | "a `ui_step` call, an escalation" | The cloud `ui_step` and its bounded escalation are M3 scope (decisions 26 and 30). M1 scope's agent team line does list "escalation through ModelRouter", but §3.2.1's M1 line names `chat_voice` and `offline_grammar`, and it defines escalation for `vision_step` and `ui_step` |
| M1-TASKLOG-01 (#73) | "one inbound VTB/app-agent task"; the stored frames of the fixture screen (IME masked, notification contents stripped, secure regions as wireframe) | Inbound `IVosAgent` and `agent.goal` are M5 scope; screenshot capture and redaction are listed in M3 scope, and M1 scope lists no screen capture |
| M1-TEAM-02 (#146) | "with Planner, Vision, and Operator busy" | Planner and Vision are stubs or thin wrappers in M1 and grow in M2–M4 (§3.7 "v0 shape") |

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
