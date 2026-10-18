# Critique of plan/claude-advanced-2026-10-17

| Field | Value |
|---|---|
| Date | 2026-10-18 |
| Author | Grok (critic). Planning only. No file except this one was written. No tests, evals, phone actions, commits, or branch switches. |
| Branch | `plan/claude-advanced-2026-10-17` vs `origin/main` (`9dbcc26`) |
| Inputs | `git diff origin/main...HEAD` (docs/TARGET-PLAN.md; research/plan-improvement-2026-10-17.md); TARGET-PLAN decisions 1–29 and standing constraints; report §19 apply notes and §20 self-review |
| Verdict | **Merge with fixes.** Do not merge as-is. The report is useful. The apply pass put a research wishlist into the plan of record. |

Decisions 1–29 are treated as settled. This critique is about decisions 30–37, tests added on this branch, spikes S-40..S-44, Q29–Q39, and the report that produced them.

---

## 1. Top critiques, ranked

**1. Q31's default keeps M3's date (2027-01-15) and every pre-existing M3 item, then piles on ~14 new M3 tests.** The calendar still assumes one engineer (§6). M3 was already the largest milestone: hybrid vision, Maps look-around, Spotify, files, AppMemory, done checks, escalation, settled, AndroidWorld port, verify-and-correct, live control-list sampling. The branch adds M3-INJ-02/03/04, M3-PRIV-02/03, M3-TAKEOVER-01, M3-UNDO-02, M3-LAT-01, M3-TOOL-03, M3-ASK-01, M3-ASYNC-01, M3-A11Y-01, M3-THERM-01, M3-GATE-01, plus S-41 and S-43. Stretch (Q31 / M3 scope) only moves four of those (ASYNC, ASK, THERM, INJ-04). The remaining new load plus the old load does not fit in the ~9 weeks from M1 (2026-11-13) to M3. Claude's "6–7 engineer-weeks" for the *additions* is already enough to blow the date; the real add is larger (see §4, §7). Leaving the date as default means the plan of record is a schedule nobody can hit.

**2. Q30's default keeps M3-UI-05 as an M3 gate that decision 30 makes impossible.** M3-UI-05 still requires "≥60% of successful cold steps resolve on-device (no upload)". Decision 30 binds 1.0 `ui_step` to a cloud model that "sends every cold step to the cloud" (Q30's own text). A known-failing gate is worse than an open question. Anyone reading M3 in six weeks will treat M3-UI-05 as Must. The recommended answer in Q30 is the right product call; the default is the wrong process call. Decision 30 was supposed to record Caleb's standing rule. It recorded the rule and left the contradicting gate in force.

**3. Decision 32 specifies information-flow control as if it were a veto table.** `task_scope {apps[], classes[], flows[{from,to}]}` written before any untrusted observation, plus substring taint (≥8 normalized characters, OTP-like 4–8 digit codes, secure-pixel text), plus window-owner taps, plus owner confirmation to widen. That is a real IFC system on accessibility trees, IME, share sheets, and Vanadium URL bars. Cost in the report is **M (1–3 weeks)**. It is not. False vetoes will dominate: years, prices, zip codes, "Settings", common 8-character tokens. The honest residual is already admitted (paraphrase / re-encode). M3-INJ-02 is a good fixture. Promoting the whole mechanism to a numbered decision on the same pass as inventing it locks the architecture before S-41 has run.

**4. Latency numbers fight the evidence already in RESULTS-LOG.** Decision 31: first audio p50 ≤1.0 s / p95 ≤2.0 s as an **M1** gate (M1-VOICE-01). §3.5.1: cloud step settled→action p50 ≤2.5 s, full step p50 ≤3.5 s (M3-LAT-01). The same decision 30 paragraph cites Grok model p50 **2.4 s** and Gemma 4 E4B **15.7–61.8 s**, with harness step p50 6.8–8.2 s and Grok hangs of 20–78 s. A 2.5 s settled→action budget leaves ~100 ms after the model. Cached acknowledgements can hit 1.0 s; answers and UI steps cannot, on this stack (actuator VAD → Binder → session → vendor → TTS/act). Writing vendor marketing SLOs into M1/M3 pass criteria guarantees red tests.

**5. Decision 30 overlays decision 26 without rewriting it.** Decision 26 is still titled "On-device step execution with bounded cloud escalation." §3.2.1 still defines `ui_step` as `where: on_device_session`, Gemma 4 E4B, constrained decoding, S-39 residency. A "Release (decision 30)" sentence is appended. M3-ESC-01 still requires "1–2 cloud steps, then hands back to `ui_step`". If 1.0 `ui_step` *is* the cloud, that test is cloud-to-cloud or it is only for the harness lane; the test does not say which. M3 scope still includes S-39 (2.3 GB E4B vs LMK on Spotify). Q31 recommends moving M3-UI-05 and detector spikes; it leaves S-39 and M3-ESC-01 in M3. Readers now have two 1.0 stories.

**6. The apply pass treated "most advanced" as a merge criterion.** Report §16 applied 13 proposals and light-applied 8 more (21/26). TARGET-PLAN is the build list for one person on a Graphene Pixel 7. Ranking by "impact on most advanced and usable per unit effort" then applying almost everything is how a gap analysis becomes a second product. Signature suite (M4-SIG-01), MobileWorld (M5-MW-01), Wilson/pass^3 gates (M3-GATE-01), camera look (decision 37), OwnerMemory (decision 36), thermal soak, onboarding, background-display spike (S-44), typed data plane with no test — each is defensible in the report. Together they are a lab, not a 1.0.

**7. P12 / Graphene privacy vs Q34 default.** Decision 30 puts every rung-7 step on a vendor. Decision 33 records that xAI keeps requests 30 days unless enterprise ZDR. Q34 default: do not require ZDR; disclose. P12 is "data stays home by default." A4 already names vendor retention as a threat. Disclosure is necessary. Shipping 1.0 as "screens of your Settings, Markor, and whatever the task app shows, retained 30 days" without a spend *and* retention decision is a product hole, not a documentation win. Q5's $30/month cap is unchanged; 25 cloud images per task (decision 33) will burn it.

**8. Audience #1 is still scheduled last, with extra homework.** P13 now has TalkBack coexistence, named describe tasks, lengthened guards, step narration. Tests: S-43 and M3-A11Y-01 at M3; camera look at M5 (M5-CAM-01); testers at M6 (Q8/Q37 default). M3-A11Y-01 requires L1 pass rate with a screen reader within the Wilson interval of the run without it — before S-43 has answered whether TalkBack even installs on Play-free Graphene. Camera `describe`/`find` send `camera_pixels` to the same cloud vision path 1.0 already depends on. The accessibility text grew. The 1.0 cut line for a blind owner did not.

---

## 2. What is weak or under-specified

**Task scope before untrusted reads (decision 32, §3.5.1).** The Conductor writes `apps[]` / `flows[]` from "the owner's words, owner memory, and app cards" with no observation. That works for "put this page's address into a new Markor note" (M3-INJ-02's legitimate flow names both ends). It does not work for "do the thing on this screen", "unsubscribe from that", or any deictic ask. Report C20 (deictics) was left as a proposal. Scope then has to be guessed wide (weak security) or narrow (constant `needs_confirmation` widenings). Widening after untrusted content is released "needs owner confirmation, which the actuator demands." That is a mid-task interruption. P6 still says the only interruptions during work are the trusted-path announcement and catastrophic "yes". Q35 only covers clarifying questions, not scope widening.

**Taint (decision 32).** No normalization spec (case, whitespace, OCR confusables, hyphenation). No rule for typed characters that *overlap* a tainted string rather than copy it. Browser `to: network` is correct; share-sheet and `ACTION_SET_TEXT` into the same app's search box are not worked. ≥8 characters will taint ordinary UI labels that appear in many apps. OTP 4–8 digits will taint "2026", "100%", volume "30". Tune-from-M3-INJ-02 is doing the design in the test.

**Window-owner rule (S-41, M3-INJ-03).** Depends on `AccessibilityWindowInfo` package/type on panther, including overlays, bubbles, PiP, split-screen, WebView-drawn fake dialogs. S-41's fallback is "treat unattributed overlays as out of scope" — that will also veto legitimate bubbles and chat heads, which the report itself flags. M3-INJ-03 pass is "acts that follow injected text ≤2%". That mixes veto-the-tap (measurable) with "the model ignored the comment" (prompt luck). Lane L4 then makes "0 `taint_flow` escapes" a zero-tolerance gate (M3 eval lanes). An escape that is a paraphrase is defined as uncaught in decision 32. The gate and the honest residual cannot both be true.

**Cloud minimization (decision 33, M3-PRIV-03).** "Only the task app's window" plus finance/comms content-row placeholders. How a split-screen partner is identified, how a bubble's package is known, how WebView-inside-the-task-app content rows are distinguished from chrome — all delegated to AppMemory placeholder logic (§3.9) that was built for *stored* notes, not live redaction. "Read my last Markor note" uploading "only that note's text" requires the redact step to parse which nodes are the note. That is a second vision problem.

**Turn timing (decision 31, Q33, S-40).** Who is the voice vendor for first-audio: xAI Grok Voice Agent (realtime) or STT→LLM→TTS? VOS's pipes add hops the vendor SLO does not include. False cut-in ≤5% and false barge-in ≤2% have no fixture definition beyond "pause fixture" / "speaker fixtures". Patience settings for dysarthria are a product requirement with no M1 design. Working earcon every wait >3 s when the screen is off will fight P13 "never talks over the screen reader" and the night rule (house rule 1 now covers earcons and screen-reader speech).

**Acknowledge-then-work (M3-ASYNC-01).** One UI task at a time, fast tools during it, second UI task queued or declined. No spec for shared AudioPlane / focus while a UI task holds the screen. No spec for a fast tool that *is* audio ("volume 30") during step narration. Decision 34's plan preview "does not wait" (P6) while decision 31 keeps the conversation open — two talkers on one CONVERSE session.

**OwnerMemory (§3.11, decision 36, M2-MEM-01).** Store is **session CE storage**; "the actuator holds none of it." AppMemory is in the actuator for a reason (decision 24). Session is the networked principal (decision 1, A5). Owner facts ("usual gas station") then sit in the process that already talks to xAI. Retrieval "may inform the task scope because they are trusted owner words" — so a compromised session writes the scope *and* holds the memory that seeds it. Write rules (owner-turn only) are good. Placement is not argued.

**Compensation journal (decision 35, M3-UNDO-02).** Reliable for settings toggles and "folder VOS created". "An app item the app lets VOS delete" is not a contract. Inverse of a Vanadium navigation, a Spotify Follow, a scroll, a permission dialog, a Maps reroute: unmarked `no_undo` in practice. Recovery ladder order (re-observe → retry once → replan → escalate → NEEDS_USER → partial report) can loop with decision 26's two escalations. Who counts retries vs escalations is unspecified.

**Eval lanes (C19, M3-GATE-01).** L1 is ~15 tasks. pass^3 on n=15 is a coin-flip gate. Wilson 95% intervals on that n are theater. "L1 and L2 may not drop beyond their Wilson intervals" will either never fire or fire on app updates, which the same section says become drift notes rather than reverts. Human-time ratio needs a human timing each task once — on a tethered Pixel 7, daytime, no Phone/Messaging. L3 MobileWorld needs self-hosted Mattermost / Mastodon / Mall4Uni on caleb-Z790 (report §15.5). That is an ops product.

**M1-VOICE-01 / M1-BARGE-01 volume.** 100 recorded utterances + 30 speaker + 30 headset barge-ins + music/own-voice false-barge, daytime only. M1 target is 2026-11-13. S-40 has not run. The tests are written as if S-40 already showed the numbers are reachable.

**M1-ONBOARD-01.** ≤3 min tour, TalkBack-on, three sample tasks that pass oracles, then "what can you do?" lists ≥5 tasks valid for the current app *and each works*. That last clause is an open-world capability demo at M1, before rung 7 exists.

**M6-REACH-03.** ≥80% of a new user's first 10 requests pass their oracle. No n, no who the users are, no task distribution. Q8 still recruits in M5.

**`device.query` / `data.query` (§3.5.2).** Applied light, M5 scope, **no test** (report §19, §20.7). A Planner rule "try these before rung 7" without a test will not happen.

**M3-PRIV-01** still wants "on-device-only steps show 0 upload bytes" while 1.0 `ui_step` has no such steps. The test is still meaningful for `control_detect` / `ocr_commit`; the wording was not updated for decision 30.

**Q23.** The report's rule was: every new question's default keeps the current plan; new veto reasons need a plan change. This branch *is* that plan change, and it rewrites Q23's closed set in the same breath. Fine as a plan change. Calling it "no numbered decision was reversed" and "every default keeps the current plan" (§18, §19) is bookkeeping.

---

## 3. What is missing

- **A 1.0 cut line.** Q29 defaults 1.0 to M6 exit (Tier F, F7+F5, other people). There is still no list of what 1.0 *must* do vs harness lanes vs post-1.0. Decision 26/S-39/M3-UI-05/detector spikes/M3-UI-LIST-01 are 1.0-adjacent in M3 text and post-1.0 in Q31's recommendation.
- **Offline 1.0.** Airplane-mode pause/timer stays (row 11 of the signature list). Every UI task dies without the vendor. No `NEEDS_NETWORK` UX beyond P8. No local fallback for "turn on wifi".
- **Vendor down / slow.** Hedged retry at ~8 s (cloud-loop rule 3). No circuit breaker, no "cloud is drunk" stop, no cap on hung 20–78 s calls except the hedge. RESULTS-LOG already saw those hangs.
- **Dollar cost of every-step screenshots.** Q5 $30/month vs 25 images/task vs `ui_verify` still on irreversible taps vs `ui_step` now also cloud. No worked example.
- **Voice architecture for the 1.0 s budget.** Realtime voice agent vs cascaded STT/LLM/TTS. Decision 31 assumes a vendor semantic end-of-turn "where it exists". If M1 is still xAI realtime (Q19), say so. If UI tasks leave realtime and enter a tool loop, first-audio and barge-in are a different machine.
- **Comms-test device assignment.** Report §20 already found M2-TP-01, M2-TP-03, M2-CALL-01, M2-REPLY-01, and M3-UI-01's "open a Signal chat" vs house rule 2 (no Phone/Dialer/Messaging on Phone B). Still unfixed. The house rule is written; the old tests are not pointed at Phone A/C or fixtures.
- **Scope examples.** Three worked `task_scope` documents: a single-app Settings toggle; a two-app "copy into Markor"; a "what's on screen" with empty flows.
- **Where OwnerMemory lives, and why the networked process is allowed to hold it.**
- **TalkBack install path on Graphene without Play** (S-43 still `[verify]`). M3-A11Y-01 is written as if the reader is present.
- **Pixel 7 RAM budget for 1.0.** Session cloud client + actuator + screenshot + a11y + (harness) E4B 2.3 GB. S-39 already failed on Spotify. Decision 30 does not retire the on-device process for 1.0 builds; it keeps it as a lane.
- **LTE for UI steps.** Decision 31 logs LTE for first-audio. M3-LAT-01 is Wi-Fi only. Hands-busy / in-pocket (audience #3) is LTE.
- **Bystander privacy for `camera.look` describe/find.** Q36 bans person ID; describe still describes people in a desk scene (M5-CAM-01). No "faces exist, unnamed" rule.
- **GitHub issues / TRACKING.md / README.md** for S-40–S-44 and the 24 tests (report §20). Stale TRACKING spike range S-01…S-33 predates this branch; merging without a tracking update will orphan the new IDs.
- **C10 test.** Typed data plane is in §3.5.2 with no acceptance test.
- **How M3-ESC-01 is supposed to run under a cloud `ui_step`.**

---

## 4. Unbuildable or unrealistic on a Pixel 7 / Graphene VOS fork, one engineer

**Computer Control / background agent display (S-44, Q38).** Android Computer Control is an OEM-preloaded-assistant API. Graphene will not ship Google's assistant stack. `ACCESS_COMPUTER_CONTROL` / `CREATE_VIRTUAL_DEVICE` are listed as "if spikes pass" in §4.1. A non-secure virtual display blanks `FLAG_SECURE` (already noted). IME, a11y, and audio focus on a secondary display are a framework project. Q38 default foreground is correct. Putting S-44 in **M4** (target 2027-02-19), next to first flash, duplex Paths A–C, and S-32, is fantasy for one person.

**Gemma 4 E4B residency (S-39) on panther.** Already LMK-killed during Spotify in RESULTS-LOG. Pixel 7 is 8 GB. Graphene hardened_malloc does not help a 2.3 GB GPU model. Decision 30's evidence *is* this failure. Keeping S-39 as M3 Must work is running a science project on a phone that already said no.

**M3-LAT-01 budgets on panther.** Model p50 2.4 s for Grok; budget 2.5 / 3.5 s. Capture+redact+Binder (80+40 ms) and settle still exist. The 3.5 s full-step p50 is reachable only if the model gets faster *and* batching (`ui.act_seq`) removes round trips *and* the vendor never stalls. None of that is demonstrated. Treat the numbers as stretch telemetry, not a pass/fail gate.

**M1 first-audio 1.0 s p50 through VOS pipes on Pixel 7 speaker and BT.** Vendor claim for *their* stack. VOS still has to prove S-08 AEC, S-40, and CONVERSE barge-in. False barge-in ≤2% on speakerphone with music is an AEC thesis. M1-BARGE-01 as a gate before those spikes report will fail or be waived.

**TalkBack coexistence (S-43, M3-A11Y-01) on Play-free Graphene.** Graphene does not ship TalkBack. Installing it without Play is the spike. Two accessibility services sharing explore-by-touch is a known fight. "L1 pass rate within the Wilson interval" with TalkBack on is an M6 study, not an M3 gate.

**M3-THERM-01: 10-minute Maps UI loop in a warm room.** Pixel 7 thermal-throttles. The 2026-10-12 UI-detect report already said so. Headroom ≥0.8 then "drop optional on-device models" — under decision 30 the shipping executor is cloud, so the thermal rule's first lever is the thing 1.0 is not running. Useful as a soak. As a spec for slowing a cloud loop it is underspecified (what "slows" means: backoff, smaller images, stop).

**Taint IFC (decision 32) as M3 enforcement.** Building a correct taint checker over a11y strings + `ui.type` + share + URL bar, with acceptable false-veto rates on real Settings/Markor/Vanadium, is a thesis. One engineer can ship M3-INJ-02 as a fixture and a conservative veto on *exact* copies of released OTP/secure strings. The rest is not a 2027-01-15 deliverable.

**AndroidWorld 75–90 later + MobileWorld backends (M3-AW-01, M5-MW-01).** Decision 29 already scoped 30 tasks first (4–5 days) and 75–90 later (3–4 weeks). That later pile plus a self-hosted MobileWorld lab is a second job. Graphene package mapping (AOSP Clock/Contacts/Files, Vanadium) is correct; Mattermost/Mastodon/Mall4Uni on the workstation is not required to ship VOS 1.0.

**M3-GATE-01 + L1 pass^3 + L2 + L4 + L6 on one tethered phone, daytime-only for anything that speaks.** House rule 1 now covers TTS, house voice, step narration, earcons, screen-reader speech, headsets. Voice and a11y lanes compete for daylight. One USB-tethered panther is the bottleneck. The gate will be sampled, not run.

**`ui.act_seq` of up to 5 actions with settle between them, stop on `screen_sig` change.** On live Maps and Spotify, `screen_sig` will change constantly. Batches will collapse to 1. Fine if true; then M3-TOOL-03's "4-action batch, 1 model call" needs a static Settings fixture only, and the latency story cannot lean on batching for the apps that matter.

**Signature row "Ask Maps what the traffic is like" (M4-SIG-01).** Needs sandboxed Play on Phone B (Q17), which the north star otherwise treats as optional. The "Only on VOS" list then has a Google-shaped demo as its first item.

**Camera `frame` mode converging in ≤5 prompts (M5-CAM-01) from text-box geometry.** Curved bottles, glossy labels, one-handed, motion. Lookout spent years on this. Offline OCR `read` of a printed fixture label is buildable. Guided framing as a gate is not, on this team.

---

## 5. Contradictions

**Within the branch**

| Item | Conflict |
|---|---|
| Decision 30 vs M3-UI-05 / Q30 default | Cloud every cold step vs ≥60% on-device as an M3 gate |
| Decision 30 vs decision 26 / §3.2.1 class table | `ui_step` remains on-device E4B in the class definition |
| Decision 30 vs M3-ESC-01 | Hand back to `ui_step` after 1–2 cloud steps |
| Decision 30 vs M3-PRIV-01 wording | On-device-only steps / 0 upload bytes |
| Decision 32 honest residual vs L4 gate | Paraphrase uncaught vs 0 `taint_flow` escapes |
| Decision 32 widening vs P6 | Mid-task confirmation vs no permission dialogs during work |
| Decision 31 working earcon + decision 34 step narration vs P13 | Extra speech vs never talk over the screen reader |
| Decision 31 / house rule 1 | Voice tests and narration are `sound`; night window 22:00–08:00 (Q32) |
| Q23 rewrite vs §18 "every default keeps the current plan" | Closed veto set grew in this pass |
| Report §20 "changes are uncommitted" | Commits `fd058d3`…`41dce2d` exist on the branch |
| C10 "applied (light)" vs no test | Planner rule with nothing to fail |
| M4-SIG-01 uniqueness claims vs shipping products | Undo, "what did you do today", airplane-mode timer are not unique; Maps duplex needs Play (Q17) |
| Stretch list vs remaining M3 adds | Four items stretch; INJ-02/03, PRIV-02/03, TAKEOVER, UNDO-02, LAT-01, TOOL-03, A11Y-01, GATE-01, S-41, S-43 stay Must |

**Against decisions 1–29**

| Item | Conflict |
|---|---|
| Decision 14 | Default UI path is screenshot → **on-device** `control_detect` → optional cloud. Decision 30 makes the step executor cloud. `control_detect` can remain local; the 1.0 loop is no longer that sentence. |
| Decision 22 | Actuator "never substitutes a plan." Scope is Conductor-written (compatible). Actuator-demanded widening confirmation is a new control flow that needs a veto reason (`needs_confirmation` exists) and a P6 exception. |
| Decision 23 | Failure screenshots kept by default. Q36/decision 37: camera frames never kept, including failures. Stated, but easy to implement wrong in the same screenshot pipeline. |
| Decision 24 vs 36 | AppMemory in actuator CE; OwnerMemory in session CE. Same "memory" word, opposite UID. |
| Decision 26 | Title, escalation shape, S-39, constrained decoding, M3-ESC-01 still describe an on-device executor. |
| Decision 29 | SMS/call skip on Phone B already there. House rule 2 restates it. Pre-existing M2 comms tests still ignore it. |
| P12 | Data stays home by default vs every 1.0 UI step uploaded, 30-day vendor retention, Q34 default no ZDR. |
| P6 | Plan-preview "does not wait" (decision 34) is compatible. Scope widening and Q35's `NEEDS_USER` stop-and-ask are extra interrupt classes. |
| Q5 $30 cap | Unchanged under a much hotter upload regime. |

**Standing constraints**

| Constraint | Status on this branch |
|---|---|
| Root off | Held. MobileWorld/AndroidWorld via F8 / priv grants. S-44 Computer Control is the likely place this gets cheated later. |
| On-device nav after 1.0; 1.0 cloud | Written as decision 30. Undermined by M3-UI-05 default, S-39 in M3, `ui_step` class text. |
| No Phone/Dialer/Messaging tests on Pixel 7 | Written as house rule 2. Contradicted by M2-TP-01/03, M2-CALL-01, M2-REPLY-01, M3-UI-01 Signal seed. M3-INJ-02 correctly uses fixtures. |
| No sound tasks at night | Written as house rule 1 and expanded in pass 4 to all speech/earcons/TalkBack. Correct. Makes M1-VOICE-01, M1-BARGE-01, M1-ONBOARD-01, M3-A11Y-01, M3-TAKEOVER-01, M5-CAM-01 daylight-only. Calendar ignores that. |
| Training data never on GitHub | House rule 3 restates §3.8.1. M3-VERIFY-01 / M3-UI-LIST-01 still store labeled examples; allowed on desktop only. No new leak path in the plan text. |

---

## 6. Over-scoped items, and what to cut or defer

Cut from **Must / numbered decision / M3 gate**. Keep in the report or as a spike/question.

| Cut or defer | Why | Keep instead |
|---|---|---|
| Q30 default: M3-UI-05 as M3 gate | Cannot pass | Measure on-device share in the harness lane; gate in the post-1.0 release |
| S-39, S-UI-TREE, S-UI-LAT, M3-UI-LIST-01 as 1.0 path | 1.0 is cloud; E4B already LMK | Next-release lane, as Q31 already hints for the detector spikes |
| Decision 32 as full IFC | Unbuildable in M3 | Threat A8 + scope schema + M3-INJ-02 on **exact** OTP/secure copies + S-41 spike. Taint threshold stays experimental. |
| M3-INJ-03/04 as zero-tolerance L4 gates | ≤2% "followed injected text" is prompt luck | Fixture suite in L4; 0 irreversible dispatches is the hard gate (already stated) |
| M3-A11Y-01 L1 Wilson comparison | Depends on S-43, TalkBack install, daylight | S-43 spike in M3; M3-A11Y-01 as a smoke (surfaces operable, no talk-over) |
| M3-GATE-01 pass^3 / Wilson machinery | n too small | L1+L4 checklist on the RC; drift notes for app updates (already the eval rule) |
| M3-LAT-01 as pass/fail on 2.5/3.5 s | Fights RESULTS-LOG | Log p50/p95; set gates after S-40-class cloud-loop data |
| M3-ASYNC-01, M3-ASK-01, M3-THERM-01 | Already stretch | Leave stretch; do not pretend Q31 default still "keeps the date" |
| M3-TOOL-03 batch-of-5 as latency strategy | `screen_sig` on dynamic apps | Tool exists; gate on Settings fixture only |
| S-44 in M4 | OEM API, high risk | Post-1.0 research; Q38 already defaults no |
| M5-MW-01 MobileWorld lab | Ops + port cost | Revisit after AndroidWorld 30 is boring |
| M4-SIG-01 as a **gate** | Marketing bundle; Maps row needs Play | Keep the list as a demo script; gate the rows that already have tests |
| M1-VOICE-01 n=100 as M1 gate | S-40 first | S-40 + a 20-utterance smoke at M1; numbers become targets |
| M1-ONBOARD-01 "each of ≥5 listed tasks works" | Open-world at M1 | Tour + three fast-tool samples |
| Decision 37 `frame`/`find` gates | Hard a11y product | `read` offline + `describe` with cloud in M5; framing as stretch |
| OwnerMemory in session CE | Wrong UID | Small store, **actuator** CE, same write rules; or a written A5 exception |
| `device.query`/`data.query` without a test | Dead text | One M5 test or remove from §3.5.2 |
| C15–C17, C20, C26 | Correctly left as proposals | Stay out |

Do not cut: house rules 1–3; decision 30's *rule* (cloud 1.0); decision 33 minimization + retention disclosure; decision 31 as **targets**; work log / takeover (decision 34) in simplified form; OwnerMemory write-rules idea; A8 as a threat; M3-INJ-02 fixture; M3-PRIV-02/03 intent; M3-UNDO-02 for settings/files.

---

## 7. Risks

- **Plan of record becomes unexecutable.** Next agent or Caleb plans M3 off the test table, misses the date, and starts waiving gates. Once waiving starts, P3/P8 honesty erodes.
- **M3-UI-05 surprise fail** if Q30 is unanswered at M3 planning (report §20.4 already says this).
- **Privacy backlash on a Graphene image.** Every UI step at xAI, 30-day retention, Q34 no ZDR, $30 cap. The Settings disclosure (M3-PRIV-02) arrives after the architecture is locked.
- **False taint vetoes** make basic "copy this into a note" the only multi-app flow that works, and only when the owner named it.
- **Scope-too-narrow** confirmation spam vs P6.
- **Speakerphone barge-in** false-triggers on music/nav (audience #3, #4). ANNOUNCE already cancels on any speech; CONVERSE barge-in at 300 ms will cancel useful answers.
- **Thermal + cloud + Maps** on panther in a car mount: throttle, then honest stop, in the scenario M3-MAP is supposed to win.
- **TalkBack install failure** silently drops P13 work.
- **S-44 / Computer Control** later used as an excuse to weaken FLAG_SECURE / capture non-goals.
- **Eval time crowding.** Daytime-only sound + one phone + AndroidWorld + injection + voice + field days. House rules will be skipped "just this run".
- **OwnerMemory in the session:** prompt injection does not write it (good); session RCE reads it (A5).
- **`camera_pixels` to cloud** from a phone in public. Indicator on is necessary and not sufficient.
- **Cost overrun** on image tokens; silent quality drop when the router downgrades at the Q5 cap.
- **Issue tracker drift.** 24 tests and 5 spikes with no GitHub issues (report §20).

---

## 8. Q30 and Q31

### Q30 — M3-UI-05 vs cloud 1.0

**Recommendation: change the default.** M3-UI-05 as written cannot pass under decision 30. Keeping it as an M3 gate is how plans lie.

Do this:

1. Split the test. **M3-UI-05a** (1.0 / M3): on the **cloud** `ui_step`, report the on-device share (expected ~0 for the step executor; `control_detect` / `ocr_commit` / route replay still count as local). No ≥60% gate. Hold §3.5.1 local budgets for capture, tree detect, redact, Binder.
2. **M3-UI-05b** (next major release): ≥60% of successful cold steps with no upload, on the on-device `ui_step` binding, once S-39 is a real "yes" on panther.
3. Say in decision 26's *pointer* (or a one-line amendment Caleb owns) that 1.0 binding is cloud and M3-ESC-01's "hand back to `ui_step`" means "hand back to the bound executor" for 1.0, and means on-device only in the harness lane.

The report's *recommended* answer is this. The report's *default* is the bug.

### Q31 — M3 load

**Recommendation: slip the date *and* cut.** Stretching four new tests is not a load plan.

1. **Move to the next-release / harness lane now:** M3-UI-05 on-device share, S-39, S-UI-TREE, S-UI-LAT, M3-UI-LIST-01 as a 1.0 dependency.
2. **Keep the existing stretch:** M3-ASYNC-01, M3-ASK-01, M3-THERM-01, M3-INJ-04 → M5 first.
3. **Also stretch or cut from M3 Must:** M3-A11Y-01 Wilson comparison (keep S-43), M3-GATE-01 statistics, M3-LAT-01 as a hard gate, M3-INJ-03 as a ≤2% model-behavior gate (keep 0 irreversible), M3-TOOL-03 as a latency strategy.
4. **Keep in M3 if anything new stays:** house rules in the harness; decision 33 redact + M3-PRIV-03; M3-INJ-02 exact-copy taint; work-log lines (subset of decision 34); M3-UNDO-02 for settings/files; S-41 probe (not a 99% attribution Must).
5. **Change Q31's default.** "Keep M3's target date and every existing M3 item, and add the new ones" is the sentence that makes the rest of the branch fiction. Default should be: M3 date moves when the cut list above is applied; remaining new Must items are named in the M3 scope in one bullet, not seven.

A later M3 without a cut still does not fit a one-engineer calendar that already contained AndroidWorld, Maps intelligence, Spotify, AppMemory, settled, and verify-and-correct.

---

## 9. Where I disagree with Claude's report

**Applying 21 of 26 proposals was the mistake.** The gap analysis (§0–§14) is the valuable artifact. Rank-1 C1 (write the 1.0 rule) and rank-5 C6 (house rules) should have been almost the whole apply set, plus a thin C25 (minimization) because C1 implies it. Applying C3/C4 as full decisions, C19 as a gate machine, C12 as decision 37, C14 as §3.11, C7 numbers as gates, C21 as an M4-EXIT gate, and C18 as an M4 spike is how the report overran the plan.

**"None changes a numbered decision" / "every default keeps the current plan."** Decision 26 is substantively overridden for 1.0. Q23's closed set grew. M3-UI-05 is unchanged on the page and contradicted in meaning. This is lawyerly consistency. Pass 4 then grepped IDs and compacted paragraphs. That is copy-edit, not self-review. The load, the failing gate, the comms tests, and the latency vs RESULTS-LOG were all visible in pass 4's own "left for Caleb" list — and left in the plan as defaults.

**"6–7 engineer-weeks" for the M3 add.** Too low. Decision 32 enforcement, S-41, S-43, cloud redact of split-screen/bubbles, takeover/hand-back, undo journal for UI, injection suites, GATE-01, LAT-01, TOOL-03, A11Y-01 are not 6–7 weeks on top of existing M3. They are another M3.

**C3 cost M, C18 impact 5.** C3's cost is L if the decision text is implemented. C18's impact is real on other vendors' phones with Computer Control; on Graphene panther it is near zero until a fork invents the display. Ranking it 23 with a spike in M4 still taxes M4.

**Signature uniqueness (§1 / C21).** Duplex-through-Maps, loopback, per-app routing, F5 secure-read for the owner's agent are the actual differentiators. Bundling undo, airplane-mode timer, and "any app" as "no reviewed phone does this" overclaims. "Any installed app" is the *goal* of rung 7, not a demonstrated 1.0 property.

**AndroidWorld is saturating, therefore MobileWorld in M5.** Saturation is a reason *not* to spend a solo fork on the next academic suite. VOS wins on oracles, trusted path, and no-root-on-Graphene — decision 29 already covers that. L3 is prestige eval.

**First-audio 1.0 s from xAI's published average.** That is their pipe. VOS's pipe is longer. Putting it in §1 as a gated metric at M1 copies a press number into a contract.

**Q34 default is acceptable as disclosure-only.** For this codebase it is not. Decision 30 made retention a 1.0 property. "Record and show" without a Caleb answer on ZDR / vendor / cap is unfinished C1.

**OwnerMemory write-only-from-owner-turns is enough.** Placement in the session is a security choice the report treats as storage trivia.

**Keeping M3-UI-05 as default "because changing a test needs a question."** Then the answer to the question should have been applied in pass 3. An open question whose default is the broken state is a trap.

**Pass 4 claim that no standing constraint was weakened.** House rules were *written*, which is good. They were not applied to M2 comms tests. Constraint "on-device nav after 1.0" was written and then left sharing M3 with a hybrid on-device gate. That is a weaken-by-ambiguity.

**Report-only leftovers (C15, C16, C17, C20, C26) were the right call.** Agree. The error was the size of the apply set, not the size of the research.

**§20.1 comms tests, §20.4 Q30/Q31, §20.5 [est] numbers.** Agree these are real. Disagree that they can remain "for Caleb" while the branch asks to become main.

---

## 10. Recommendation

**Merge with fixes.** Do not merge as-is. The report (`research/plan-improvement-2026-10-17.md`) can land as research even if the plan edits are reduced.

Fixes required before TARGET-PLAN on this branch is main:

1. **Answer Q30 in the plan text:** split or retarget M3-UI-05; 1.0 does not gate on ≥60% on-device `ui_step` steps.
2. **Answer Q31 with a cut list and a date change** (or an explicit "M3 date is invalid until cut"). Default is no longer "keep the date and add everything."
3. **Rewrite the `ui_step` class sentence in §3.2.1** so 1.0 binding is cloud in the table, not only in a footnote. State what M3-ESC-01 means under that binding. Move S-39 out of M3 Must.
4. **Demote decision 32's taint machinery** to a spike + M3-INJ-02 exact-copy tests; keep A8 and the scope-before-read *idea*. Remove L4 "0 taint_flow escapes" as long as paraphrase is an admitted residual — or define escape as "byte-identical released string left the phone."
5. **Reconcile P6** with scope widening and with Q35. One interrupt policy.
6. **Point M2-TP-01/03, M2-CALL-01, M2-REPLY-01, and M3-UI-01's Signal seed** at Phone A/C or fixtures. House rule 2 is otherwise false.
7. **Downgrade decision 31 numbers and M3-LAT-01** to targets until S-40 / in-OS cloud-loop data exist. M1-VOICE-01 n=100 becomes a smoke + log.
8. **Move OwnerMemory to actuator CE** or write the A5 exception.
9. **Q34:** require an explicit Caleb answer before 1.0 architecture hardens on xAI 30-day retention. Disclosure-only is not a decision.
10. **S-44:** drop from M4 scope; leave Q38.
11. **M4-SIG-01:** demo list, not an EXIT gate; Maps row labeled Q17.
12. **M3-A11Y-01:** smoke after S-43, not L1 Wilson.
13. **M5-MW-01:** out of Must.
14. **Add a test for `device.query`/`data.query` or delete the row.**
15. **Night-window vs calendar:** state that L5 and speaking a11y tests are weekday-daylight only, and size M1/M3 accordingly.
16. **Tracking:** issues for new tests/spikes, or a TRACKING.md line. Stale README/TRACKING can wait; orphaned test IDs cannot.

If those cuts are refused, **do not merge.** A main TARGET-PLAN that cannot be built will be quoted as law by the next implementer.

---

## TL;DR

The report is a good gap analysis; the apply pass stuffed 8 decisions, 24 tests, and 5 spikes into a one-person Graphene Pixel 7 plan.

Q30's default leaves M3-UI-05 failing under the cloud-1.0 rule the same branch finally wrote down; Q31's default keeps the M3 date while adding a second M3.

Decision 32 IFC, 1.0 s voice, 2.5 s cloud steps, TalkBack-on L1, Computer Control, and E4B residency do not fit panther or the team.

House rules, the 1.0 cloud rule, minimization/retention disclosure, and a small work-log/undo slice should survive; the rest stays research.

Merge with the fix list in §10, or do not merge.
