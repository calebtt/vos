# Decisions on Grok's critique of plan/claude-advanced-2026-10-17

| Field | Value |
|---|---|
| Date | 2026-10-18 |
| Author | Claude (Claude Code), delegated by Caleb: "Have Claude decide with Grok's feedback." |
| Input | research/plan-critique-grok-2026-10-18.md (§10 fix list and §1–§9); docs/TARGET-PLAN.md; research/plan-improvement-2026-10-17.md; commit 7d942ea (on-device leftovers, Q30) |
| Applied to | docs/TARGET-PLAN.md (change-log row "(2026-10-18) Claude decided Grok critique items, delegated by Caleb"); docs/TRACKING.md (spike range, open item); research/plan-improvement-2026-10-17.md (§20 note) |
| Counts | **48 items: 27 accepted, 11 accepted in part, 7 rejected, 3 done (7d942ea).** Of Grok's 16 §10 fixes: 11 accepted, 3 in part, 2 done. |

Standing constraints were not loosened. House rules 1–3 and the night window are unchanged, and two were tightened: daytime calendar sizing and the comms device rule. Phone B (Pixel 7) still never reaches Phone, Dialer or Messaging, and training data stays local. Money and data-leaving-the-phone items (Q5, Q34) stay open for Caleb.

## A. Grok's §10 fixes

| # | Grok item | Verdict | Reason | Where applied |
|---|---|---|---|---|
| A1 | Answer Q30; M3-UI-05 must not gate ≥60% on-device under a cloud 1.0 | Done (7d942ea) | Caleb's "Fix on device leftovers" split it into M3-UI-05a (report-only) and M3-UI-05b (post-1.0) | §9 Q30; M3-UI-05a; post-1.0 lane M3-UI-05b |
| A2 | Answer Q31 with a cut list and a date change | Accept | A plan of record with an unreachable date gets waived gate by gate | §9 Q31 (answered); M3–M6 headings; M3 scope bullets; §6 status words |
| A3 | Rewrite the §3.2.1 `ui_step` class for a cloud 1.0; say what M3-ESC-01 means; S-39 out of M3 | Done (7d942ea) | Decision 26 retitled, M3-ESC-01 on the cloud loop, M3-ESC-01b and S-39 in the post-1.0 lane | Decision 26; §3.2.1; M3-ESC-01; S-39 |
| A4 | Demote decision 32's taint machinery to a spike plus exact-copy tests; fix the L4 "0 escapes" vs paraphrase contradiction | Accept in part | The scope-before-read schema and the window-owner veto are buildable and stay Must. The general substring taint is the unbuildable part, so in 1.0 taint vetoes only exact (normalized) copies of high-risk strings: secure-pixel text, and OTP-like codes from finance/comms/authenticator apps. The ≥8-character rule is log-only (`taint_candidate`). An escape is now defined as byte-identical after normalization | Decision 32; §3.5.1 taint set; M2 scope; M3-INJ-02; L4 gate |
| A5 | Reconcile P6 with scope widening and Q35: one interrupt policy | Accept | An actuator-demanded mid-task confirmation was a new interrupt class. Widening now stops the task with `NEEDS_USER`, the same as Q35 | P6; decision 32; §9 Q35 note |
| A6 | Point M2-TP-01/03, M2-CALL-01, M2-REPLY-01 and M3-UI-01's Signal seed at Phone A/C or fixtures | Accept | House rule 2 was false for these tests. Caleb's rule: Phone B can only make emergency calls and must never reach Phone/Dialer/Messaging. The rule now names the device. Phone C is used rather than Phone A, because Phone A is the daily phone | New M2 "Comms device rule"; M2-TP-01, M2-TP-03, M2-INJ-01, M2-CHAT-02, M2-CALL-01, M2-REPLY-01; S-18; M3-UI-01; M3-INJ-01; M3-FILE-01; M4-SHELL-nn rows 6–7; house rule 2 |
| A7 | Make decision 31 and M3-LAT-01 numbers targets; M1-VOICE-01 becomes a smoke | Accept | Grok's model p50 alone was 2.4 s against a 2.5 s step budget. Vendor first-audio numbers describe the vendor's own pipeline, which is shorter than VOS's. Safety-relevant checks stay gates: ANNOUNCE cancels on any speech, barge-in cancels the vendor, timestamps are logged, and the stall cap ends a stalled step | Decision 31; §1 metric rows; §3.5.1 latency table; S-40 pass; M1 scope; M1-VOICE-01 (20 utterances); M1-BARGE-01 (10+10); M3-LAT-01 |
| A8 | OwnerMemory to actuator CE, or write the A5 exception | Accept | The session is the networked principal (A5). With the store in the session, a session compromise could read it all and rewrite what seeds the task scope. The store moves to the actuator; writes are accepted only in an actuator-issued `owner_turn` and read back in the house voice | Decision 36; §3.1 map; §3.7 Conductor row; §3.11; M2 scope; M2-MEM-01 |
| A9 | Q34 needs an explicit Caleb answer before 1.0 hardens on 30-day retention | Accept in part | Agreed that disclosure alone is not a decision. Per the delegation rules this stays open. The default is tightened: pre-1.0 builds disclose only; the 1.0 build sends rung-7 screen uploads only to a ZDR or no-retention vendor. Marked "needs Caleb's answer before M3 planning" | §9 Q34 |
| A10 | S-44 out of M4; leave Q38 | Accept | Computer Control is an OEM-assistant API and Graphene ships none; on one engineer's M4 it is a framework project | S-44 Needed by; M4 scope; §6 Backlog; Q38 note |
| A11 | M4-SIG-01 a demo list, not an exit gate; label the Maps row Q17 | Accept | The rows bundle existing tests, and several rows are not unique. It is now report-only; each row's own test is the gate, plus a hard 0 Phone/Dialer/Messaging contact | §1 "Only on VOS" text; M4 scope; M4-SIG-01 |
| A12 | M3-A11Y-01 a smoke after S-43, not an L1 Wilson comparison | Accept | TalkBack's install path on Play-free Graphene is unknown (S-43), and an L1 Wilson comparison is an M6 study | M3-A11Y-01 (3 L1 tasks, `blocked: S-43` if no install path) |
| A13 | M5-MW-01 out of Must | Accept | AndroidWorld already covers the benchmark role (decision 29). MobileWorld's self-hosted backends are ops work | §6 Backlog (M5-MW-01 row moved there); L3 lane label; M5 scope |
| A14 | Add a test for `device.query` / `data.query` or delete the row | Accept (test added) | A Planner rule with nothing to fail will not happen. One small test is cheaper than losing the feature | New M5-QUERY-01; §3.5.2 row; M5 scope |
| A15 | Night window vs calendar: sound tests are daylight-only; size M1/M3 for it | Accept | It tightens house rule 1's scheduling without changing the window | House rule 1 "Calendar" sentence; M1 scope line; M1/M2/M3 tests marked daytime |
| A16 | Tracking: issues for new tests/spikes, or a TRACKING.md line | Accept in part | GitHub issues were out of scope for this pass. TRACKING.md's stale range (S-01…S-33) is fixed, with an open item to create issues at merge | docs/TRACKING.md |

## B. Other substantive items from Grok §1–§9

| # | Grok item | Verdict | Reason | Where applied |
|---|---|---|---|---|
| B1 | Scope before observation fails on deictic asks ("do this on this screen"); no worked scopes (§2, §3) | Accept | The foreground package comes from the actuator, so it is trusted. Three worked scopes make the schema concrete | Decision 32; §3.5.1 "Worked scopes" |
| B2 | Taint has no normalization spec; overlaps; labels and "2026" will taint (§2) | Accept in part | Normalization is defined (case fold, strip whitespace and separators), and OTP codes count only from finance/comms/authenticator apps. OCR confusables and partial overlaps are not handled; they are part of the honest residual | Decision 32; §3.5.1 |
| B3 | S-41 fallback (unattributed = out of scope) vetoes legitimate bubbles (§2) | Reject | Failing safe is intended. The veto is typed, and the owner can name the app to widen the scope. S-41 measures how often it happens | §3.5.1 window-owner bullet (residual stated) |
| B4 | M3-INJ-03's "≤2% follow injected text" is prompt luck mixed with a veto gate (§2, §6) | Accept | The deterministic parts stay gates (100% out-of-scope taps vetoed, 0 irreversible dispatches); the follow rate is reported | M3-INJ-03; M3-INJ-04; §1 injection row |
| B5 | Cloud minimization needs live redaction of split-screen, bubbles, WebView rows; "only that note's text" is a second vision problem (§2) | Accept in part | Per-window masking from S-41 is buildable, and unattributed windows are masked. Note-level redaction is dropped. Finance/comms content-row placeholders are kept | Decision 33; §3.5.1 minimization; M3-PRIV-03 |
| B6 | No voice architecture for the 1.0 s budget: realtime vs cascade (§2, §3) | Accept in part | One line: numbers are measured on xAI realtime (Q19), and a UI task's first audio is the cached acknowledgement. No cascade design until S-40 reports | Decision 31 |
| B7 | Working earcon fights P13 and the night rule (§2, §5) | Accept | The earcon is `sound` under house rule 1 and is queued behind the screen reader | Decision 31 |
| B8 | Acknowledge-then-work lacks AudioPlane/focus spec; two talkers on one session (§2) | Reject | M3-ASYNC-01 is stretch. The spec gets written when it is picked up; adding it now adds load, which is the opposite of Q31's answer | None |
| B9 | Compensation journal: "an app item the app lets VOS delete" is not a contract; ladder vs escalation counting unspecified (§2) | Accept | 1.0 inverses are settings, toggles, and files/folders VOS created or moved; items inside other apps are `no_undo`. Counting rule added | Decision 35 |
| B10 | pass^3 and Wilson on n≈15 are theatre; the gate never fires or fires on app updates (§2, §6) | Accept | The gate becomes a checklist: L4 zero-tolerance, L1 no unexplained all-trials regression. Wilson and pass^3 are reported until a lane has ~50 tasks | M3 eval gates; M3-GATE-01 |
| B11 | M1-ONBOARD-01's "each of ≥5 listed tasks works" is open-world at M1 (§2, §6) | Accept | Now limited to fast tools; screen-reader use moved to M3-A11Y-01 | M1-ONBOARD-01 |
| B12 | M6-REACH-03 has no n or users (§2) | Accept | Sized to M6-REACH-01's 5 users × 10 requests | M6-REACH-03 |
| B13 | Q23 / "no numbered decision reversed" is bookkeeping (§2, §9) | Accept in part | The 10-17 change-log row now says decision 26 was retitled. Q23's text already says the plan change added two reasons | Feedback table row (2026-10-17) |
| B14 | No 1.0 cut line (§3) | Accept in part | A status legend defines Must, stretch, report-only, post-1.0 and backlog, and 1.0 is the Must set of M1–M6. A per-feature list would duplicate the milestone tables | §6 "Status words" |
| B15 | Offline 1.0: no local fallback for "turn on wifi"; UI tasks die (§3) | Accept in part | "Turn on wifi" is a fast tool and is added to M1-OFF-01. Offline UI tasks already stop with `NEEDS_NETWORK` (P8, §3.5.1) | M1-OFF-01 |
| B16 | Vendor down or slow: no circuit breaker; 20–78 s hangs (§3) | Accept | Stall cap: 30 s ends a step, a second stall ends the task honestly | §3.5.1 cloud-loop rule 3; M3-LAT-01 |
| B17 | Pixel 7 RAM budget for 1.0 with E4B still around (§3) | Accept | 1.0 builds do not load the on-device model | Post-1.0 lane gate paragraph |
| B18 | LTE for UI steps (audience #3) (§3) | Accept | One in-the-wild LTE session, logged apart from Wi-Fi | M3-LAT-01 |
| B19 | Bystander privacy for `camera.look describe` (§3) | Accept | Bystanders appear only as "a person" | Decision 37; M5-CAM-01 |
| B20 | `ui.act_seq` batches collapse on Maps/Spotify (§4, §6) | Accept | Batch gate only on a static Settings screen; dynamic-app batch sizes logged | §3.5.1 rule 4; M3-TOOL-03 |
| B21 | AndroidWorld 75–90 plus MobileWorld is a second job (§4) | Accept in part | M3 needs only the first ~30 tasks. Decision 29's "75–90 later" is unchanged, but placed after M3 | M3 scope; M3-AW-01 |
| B22 | M3-THERM-01 underspecified; the first thermal lever is a model 1.0 does not run (§4) | Reject (lever part done in 7d942ea) | The on-device lever is already post-1.0. Rule 7 defines "slows" (lower capture rate, prefer replay), and the test is stretch | None |
| B23 | Camera `frame` converging in ≤5 prompts is not buildable as a gate (§4, §6) | Accept in part | Framing convergence is reported (stretch). `read` offline and `describe`/`find` stay gated | M5-CAM-01 |
| B24 | Signature uniqueness overclaims (§5, §9) | Accept | The §1 text names the real differentiators (duplex, loopback, per-app routing, secure read) | §1 "Only on VOS" |
| B25 | "6–7 engineer-weeks" is too low; C3 cost is L, not M (§9) | Accept | Q31 re-estimates the remaining add after the cuts (~4–5 weeks [est]) | §9 Q31 reasoning |
| B26 | Audience #1 (blind users) is still scheduled last (§1) | Reject | Tester timing is Caleb's Q8/Q37. The M3 smoke plus Q37's optional informal session give an early signal without a new gate | None (Q8/Q37 stay open) |
| B27 | Decision 23's failure screenshots vs camera frames: easy to implement wrong (§5) | Reject | Already stated in decision 37 and §5.8, and tested by M5-CAM-01 ("0 camera frames stored") | None |
| B28 | Applying 21 of 26 proposals was the mistake; most should go back to research (§1, §9) | Reject (as a wholesale revert) | Grok's own do-not-cut list keeps decisions 30, 33, 34 (simplified), the A8 threat, minimization and undo. The over-scope is fixed by narrowing, report-only status and the backlog, not by reverting decisions | Across A4, A7, A10–A13, B10 |
| B29 | Decision 14 and M3-PRIV-01 wording vs decision 30 (§2, §5) | Done (7d942ea) | One-line note on decision 14; M3-PRIV-01 reworded | Decision 14; M3-PRIV-01 |
| B30 | Report §20 says "changes are uncommitted" though commits exist (§5) | Accept | Note added | research/plan-improvement-2026-10-17.md §20 |
| B31 | The dysarthria patience setting has no M1 design (§2) | Reject | Q33 holds the defaults and S-40 informs them; the setting is a policy value, not a new subsystem | None (Q33 stays open) |
| B32 | Decision 22: actuator-demanded widening needs a veto reason and a P6 exception (§5) | Accept (applied with A5) | Widening is now a `NEEDS_USER` stop, so no new actuator control flow and no P6 exception | Decision 32; P6 |

## How Q31 was decided

Caleb delegated the decision. Q31 is marked "answered by Claude's decision on Grok's feedback (2026-10-18, delegated by Caleb)".

- **Options considered:**
  - keep the date and add everything (the old default): rejected, because the date is unreachable;
  - keep the date by cutting the safety work (scope, window owner, minimization, takeover): rejected, because those make a cloud 1.0 safe to ship;
  - cut and slip: chosen.
- **Cuts:**
  - the on-device leftovers (7d942ea);
  - report-only status for M3-LAT-01's numbers, M3-INJ-03/04 follow rates, and pass^3/Wilson;
  - narrowed decision 32 taint, M3-A11Y-01, M3-TOOL-03, and M3-AW-01 (first ~30 tasks);
  - S-44 and M5-MW-01 to the backlog;
  - stretch list unchanged.
- **Estimate [est]:**
  - the new M3 Must work left is about 4–5 engineer-weeks;
  - moving the on-device work out frees about 1–2 weeks;
  - net is about +3 weeks on an M3 that was already the largest milestone.
- **Dates:** M3 2027-01-15 → **2027-02-05**. M4 → 2027-03-12, M5 → 2027-04-09, M6 → 2027-06-04 (each +3 weeks, since one engineer works the milestones in order). M0–M2 are unchanged; M1's load was reduced instead (voice smoke, onboarding trimmed).
- **Escalation rule:** if M3 is still late, the stretch items move to M5 first, and Caleb is asked before any Must item moves.

## Open questions left for Caleb

- **Q34: zero data retention.** Open. Recommended default, set in the plan: pre-1.0 builds disclose only; the 1.0 build uses a ZDR or no-retention vendor for rung-7 screen uploads. That may mean an xAI enterprise account or another vendor. Needs an answer before M3 planning.
- **Q5: vision vendor and the $30/month cap.** Open. Recommended: measure cost per task on L1 in M3, then re-set the cap. Default unchanged.
- **Phone C test SIM.** `device: phone_c` tests (real SMS, calls, Signal) are `blocked: no_device` until Phone C has a SIM. This is a hardware/spend item under Q12.
- **GitHub issues** for S-40–S-44 and the tests added on 2026-10-17/18. Open them at merge; docs/TRACKING.md lists this as an open item.
- All other defaults still open as before: Q2–Q15, Q17–Q29, Q32, Q33, Q35–Q39. Q35 got a note (scope widening follows it), and Q38 a note (S-44 in the backlog). Neither was answered.
