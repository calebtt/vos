# On-device app-navigation workflow for VOS: survey and recommendation

| Field | Value |
|---|---|
| Date | 2026-10-17 |
| Author | Claude (claude-opus-5-5), read-only research run (no adb, no code edits) |
| Inputs | `docs/TARGET-PLAN.md` (§3.2.1, §3.5, §3.5.1, §3.5.2, §3.7, §3.8, decisions 22–23), `vos-nav-evals/DIAGNOSIS-2026-10-17.md`, `docs/nav-evals/RESULTS-LOG.md`, `vos-task-data/failures/20261017-132738_C_phone/*/notes.md`, `vos-nav-evals/vosnav/agent.py`, plus the web sources in §F |
| Scope | Gemma 4 E4B on a Pixel 7 (LiteRT-LM, GPU), about 24 s per step, stateless per-step prompt, harness-drawn letter marks |

## Executive summary (10 lines)

1. E4B's misses are mostly **not knowing it is done** and **not tracking where it is in a sequence**. It isn't a perception problem: the observation already showed "Dark theme: off→on", and Spotify had already flipped Pause→Play.
2. The biggest cheap win is to **stop asking the model whether the task is done**. A plan-time `success_when` predicate is checked by the harness/actuator on every fresh observation (oracle over confession, P3), plus a separate text-only answer-extraction call.
3. Next: a **plan checklist plus a ≤15-word progress note** carried between steps (Mobile-Agent-v2/E, OpenPhone), and a **multi-action `tap_seq`** validated per element (UFO2, AutoDroid-V2). The calculator then costs 1 model call instead of 5.
4. **Escalate once to the cloud on stuck** (two no-progress steps or loop level 2), as OpenPhone, MAI-UI and Mobile-Agent-E do. Keep E4B as the step executor. The Conductor decides the escalation; the actuator veto still gates every act.
5. **Per-app memory** should be keyed by `package` → `screen_sig` (title + stable resource-id set), **not by package+version**. Version is staleness metadata, and TARGET-PLAN forbids version-pinned success.
6. Store text only, three types: app card (DroidRun), screen notes and transition edges (AutoDroid, AutoDroid-V2, EAM), and tips (Mobile-Agent-E). Routes/skills (SkillDroid, AppAgentX) are written **only by the actuator** from oracle-verified runs.
7. **Do not run best-of-N, a per-step critic, or a 4-role Mobile-Agent-E loop on device.** At ~16 s of model time per call, each extra role or sample adds about 16 s per step. Verification belongs to deterministic checks, or to the cloud `ui_verify` only when stuck or irreversible.
8. **Route replay is the real latency fix** for repeated tasks (no LLM per step). SkillDroid reports 49% fewer LLM calls and 2.4× faster replay; TARGET-PLAN already has rung 6.
9. Run experiments in this order, one change each: instrument → trim prompt → constrained decoding → success predicate → progress note → `tap_seq` → app cards → auto tips → cloud escalation → replay. Score strictly and add a done-detection metric.
10. TARGET-PLAN gets: an `AppMemory` component, `success_when` in the plan/task record, an on-device `ui_step` ModelRouter class with escalation triggers, and the `ui.set_toggle`/`ui.tap_seq` tools. Prompts, marks and budgets stay harness-only.

---

## 0. What E4B actually gets wrong (local evidence)

From the latest E4B run (`20261017-132738_C_phone`, 1/5 strict, model p50 15.7 s, step p50 24.5 s, ~1,260 tokens per step = 36,601 tokens / 29 steps) and the earlier runs:

| Code | Failure type | Evidence |
|---|---|---|
| **F1** | State-blind re-toggle: acts again although the state already matches | `dark_on`: S2–S6 all `tap 'Dark theme'` ("Tap the Dark theme switch to turn it on") even after it turned on; `battery_saver_on` (earlier run): 10 re-taps |
| **F2** | Overshoot: reaches the goal, does not say done, then undoes it | `spotify_pause`: S3 tapped Pause (goal met), S4 "current screen shows a 'Play' button… I will tap 'Play' to resume, then tap 'Pause' again", flip-flop until the stuck stop |
| **F3** | Reaches the target page, never extracts/reports the answer | `about_android`: reached About phone via search at S3, then tapped "Device details" twice and swiped; answer `''` |
| **F4** | Loses place in a multi-step sequence | `calc_12_times_7`: × at S5–S6 before any digits, then "Need to input 12 first" → `7`, then `1`. Nothing tells it what the display already holds |
| **F5** | Loop or oscillation | Pause/Play flip; "Device details" ×2; 18 identical taps in an earlier E4B run (DIAGNOSIS §1.4) |
| **F6** | Wrong navigation guess | Earlier run: guessed About phone was under Battery (DIAGNOSIS "Genuine model weakness") |
| **F7** | Latency | ~24 s/step, so a 6-step task takes ~2.5 min. About 9 s of each step is harness overhead (adb capture, dump, settle). The in-OS budget for that part is <0.5 s (TARGET-PLAN §3.5.1) |
| **F8** | Resource pressure | The low-memory killer killed the 2.3 GB E4B service when Spotify launched (`111851` run) |
| **F9** | Malformed output | `spotify_pause` S4 emitted two JSON objects separated by `---`; 1 invalid each in calc and Spotify |

Note: `set_toggle` exists in the action list (`agent.py:39`), but E4B chose `tap` on the Dark theme row anyway. Offering a better tool does not help unless the harness steers into it (see C-8).

---

## A. Recommended VOS app-nav workflow

The principle across the surveyed systems that fits a 24 s/step on-device model: **spend model calls only on choices**. Done-detection, state checks, sequence bookkeeping and repeated routes become deterministic actuator/harness work, so the small model is asked fewer and easier questions. Bigger models (cloud) are used once per task to plan, and again only when the small model is stuck.

```text
Owner request (voice / typed)                                     [session]
        │
        ▼
 Conductor (chat_voice) ── fast tool / VTB / app-agent typed? ──► other rungs (§3.5)
        │ UI task in <package>
        ▼
 (1) Route lookup  AppMemory.route(package, task_template)              [actuator]
        │ hit AND start screen_sig matches
        ├────────► Guarded replay (Operator, no LLM):
        │            per step: re-read tree → resolve element by multi-locator
        │            → ACTUATOR VETO → execute → expect next screen_sig
        │            mismatch → stop replay, hand state + remaining plan to (3)
        │ miss
        ▼
 (2) Plan  (Planner, ModelRouter plan_long)                              [session]
        unfamiliar app / no card → cloud, ONE text-only call (goal + app card + page title)
        familiar app with card     → E4B
        out: {subgoals[≤5], success_when (predicate DSL), answer_needed, notes}
        │
        ▼
 (3) Step loop — executor = E4B on device (ModelRouter ui_step)  [session ↔ actuator]
     a. Operator captures tree (+ screenshot), observation v2, screen_sig
     b. success_when(observation) TRUE?  ──yes──► (5)   (no model call)
     c. AppMemory.lookup(package, screen_sig) → card bullets + ≤3 tips (≤150 tokens)
     d. E4B prompt: goal · checklist (✓/→) · progress note · last 3 actions with
        state diffs · tips · observation   → constrained JSON {progress, action}
     e. Deterministic guards: schema, ids ∈ current marks, idempotent toggles,
        no repeat of a no-effect action, tap_seq validated per element
     f. ACTUATOR SAFETY VETO: allow / refuse / confirm (trusted path)
        veto {reason,class,actId} → Conductor replans, never retries around it
     g. Execute → settle → diff ("Dark theme: off→on", "Display: 12×7")
        → stuck detector: L1 hint · L2 escalate (4) · L3 honest stop
        │
        ▼
 (4) Escalation (Conductor decides; ModelRouter escalate, audited)       [session]
        trigger: stuck L2, 2 consecutive no-progress steps, 2 invalid outputs,
        or planner flagged the screen hard → ONE cloud vision_step on a redacted
        screenshot → next action or revised checklist → back to (3).
        Max 2 per task, then NEEDS_* / honest stop.
        │
        ▼
 (5) Finish                                                              [actuator]
        actuator oracle (settings value, MediaSession, foreground, readback)
        → VERIFIED;  answer_needed → text-only extraction prompt over the
        current element list (no image) → Conductor speaks result
        │
        ▼
 (6) Write-back, async, off the critical path                    [actuator + session]
        task record (§3.8) → route compile (actuator, verified success only)
        → transition edges → tip reflector (proposals, low trust)
        → training-data queue (owner-approved export only)
```

Why each piece is there:

- **(1) Replay before reasoning.** SkillDroid compiles successful trajectories into parameterized skill templates. Its matching cascade uses regex, embedding similarity and an app filter, and replay makes no LLM calls; it reports 49% fewer LLM calls and replay at 2.4× the speed. AutoDroid-V2 replaces per-step calls with one script per task and reports ~46 s vs 669 s per task on a Snapdragon 8 Gen 2. TARGET-PLAN rung 6 already says routes are recorded by the actuator and invalidated on signature failure, not on version.
- **(2) Plan once, with an explicit end condition.** Mobile-Agent-v2/E split planning from acting. MAI-UI and OpenPhone show the device/cloud split. A text-only cloud plan call sends no pixels, so the privacy cost is just the goal text and app name. `success_when` is the piece most systems leave to the model. AndroidWorld's own scoring checks state instead of trusting the agent, and VOS P3 says the same thing.
- **(3b) Check done before calling the model.** Done-detection becomes a predicate check, which fixes F2 and F1 without any model improvement.
- **(3d) Carried progress.** M3A writes a summary after every step (intended action, did it succeed, why not). Mobile-Agent-E's Notetaker aggregates task facts. OpenPhone condenses each step into a text "state assessment" so a 3B model keeps 10–20 steps of history. For E4B this becomes a ≤15-word note it rewrites each step, plus a harness-owned checklist.
- **(3e) Guards.** Mobile-Agent-E's Action Reflector sorts outcomes into success, wrong page and no change, and escalates after two consecutive failures. PokeClaw's README describes five loop signals (three named: repeated actions, unchanged screens, rising token count) with recovery escalating from hints to strategy switches to auto-kill. VOS has a version of this already (`run.py:319`).
- **(3f) Veto is last and unchanged.** Nothing here moves authority. The Conductor owns plan and escalation, and the actuator holds only the safety veto (decision 22).
- **(4) One-way vs bounded escalation.** OpenPhone hands off one way to the cloud once triggered (repetition, trajectory deviation, low action quality). VOS should use a *bounded* hand-back instead (1–2 cloud steps, then back to E4B), because the cloud is the costly and privacy-relevant path. OpenPhone reports the cloud still handled ~65% of steps, and cloud calls fell only ~10%.

---

## B. Per-app memory design (`AppMemory`)

### B.1 Key scheme

Caleb's idea (an MCP-like store keyed by an app hash) is right. **Package + version as the key is the wrong hash**: every app update orphans all notes, and one key per app is too coarse to retrieve per screen. Recommended hierarchy:

| Level | Key | Purpose |
|---|---|---|
| L0 namespace | `package` (e.g. `com.spotify.music`) | App card, routes, stats. Deleted on uninstall |
| L1 screen | `screen_sig = h(package, activity, norm(page_title), sorted(stable_ids))[:12]`. `stable_ids` = up to 20 resource-ids of clickable/checkable/editable nodes, dropping ids that repeat (list rows) and content text | Screen notes, tips, transition edges. Needed because Settings is all `SubSettings` and Spotify is all `MainActivity` |
| L1 fuzzy | Jaccard(stable_ids) ≥ 0.6 within the same package and activity → same *screen class* | Survives small layout drift. AutoDroid-V2 groups GUI states by layout similarity in the same way |
| L2 element | `(screen_sig, resource_id ‖ norm(label), class)` | Element notes: "Switch `switch_widget` on this page = Dark theme"; "Play/Pause label names the action, not the state" |
| Task template | `(package, intent_template)`, e.g. `settings.toggle(name,state)`, `media.pause`, `calc.eval(expr)`, `settings.read(field)` | Route lookup, success_when templates |

`versionCode` and `versionName` are **attributes** on every item (`seen_in_version`, `last_verified_version`), never part of the key.

### B.2 What is stored (text and structure only, never pixels)

```text
app_card     {package, bullets[≤10], source: owner|proposed|grok_teacher, state: active|candidate}
screen_note  {screen_sig, package, title, purpose(≤12 words), key_controls[≤8 {locator, role, note}],
              edges[{via_locator, to_screen_sig, count}],          ← UI transition graph
              tree_trust (decaying score, §3.5.1), seen_in_version, last_seen}
tip          {scope: package|screen_sig|element, text(≤25 words), evidence_task_ids[],
              helped, hurt, state: candidate|active|retired, created_by: reflector|owner}
route        {route_id, package, intent_template, params[], start_screen_sig,
              steps[{expect_screen_sig, locators[resource_id, text, rel_pos], action}],
              end_oracle, verified_count, fail_count, last_verified_version}   ← actuator-authored only
stats        {package, tasks, passes, escalations, common_failure_codes}
```

These pieces come from: the DroidRun/Mobilerun app cards (Markdown per package, injected into the Manager prompt); Mobile-Agent-E Tips (natural-language lessons) and Shortcuts (atomic-op sequences with explicit preconditions); AutoDroid and EAM (UI transition / knowledge graph of screens and valid transitions); AutoDroid-V2 (several locators per element so that a match on any one counts, plus forward/backward element dependencies); and SkillDroid (weighted element locators, typed parameter slots).

Example seed entries from today's failures:
- `com.spotify.music` card: "The big Play/Pause button shows the action it will do: 'Pause' visible = playing."
- `com.android.settings` tip on the About phone screen: "Android version is on this page; scroll toward end; do not open Device details."
- `com.android.calculator2` card: "Enter the whole expression with tap_seq, then '='; read the result line."

### B.3 Retrieval (cheap, no embedding model at first)

- **Task start:** exact lookup on `(package, intent_template)` → route candidate. Load the app card (≤10 bullets).
- **Each step:** exact `screen_sig` lookup, falling back to fuzzy screen class, gives the screen note plus the top 3 tips by `helped − 2·hurt`. Inject **at most ~150 tokens** into the E4B prompt. At ~1,260 prompt tokens per step now, every 100 tokens costs prefill time. Measure the per-token cost in P0 before raising this budget.
- **Later:** an embedding lookup over tip text (LiteRT-LM lists embedding models) only if exact keys miss too often. Mobile-Agent-E held 7 shortcuts and 58 tips after 25 tasks and noted that retrieval agents may be needed as memory grows. That is far from VOS scale for now.

### B.4 Write-back from task records (§3.8)

1. **On verified success (actuator):** compile or refresh a route. Drop undo pairs and no-effect steps from the trajectory. Record per-step `expect_screen_sig` and multi-locators, and increment `verified_count`. Add or bump transition edges. Increment `helped` on tips that were shown.
2. **On failure (session reflector, async):** a reflector reads the text part of the record only: actions, state diffs, page titles, veto reasons and final oracle. It proposes ≤2 tips in the `candidate` state. A Grok call is fine here because it is text only. E4B can do it later at low priority.
3. **Promotion:** a candidate tip becomes `active` after it is shown in ≥1 later run that passes without the failure recurring, or when the owner approves it. Mobile-Agent-E's failure analysis shows why this gate matters: generated shortcuts with a missing or extra tap propagated errors on reuse.
4. **Teacher seeding:** Grok's passing runs on the same apps seed routes, screen notes and edges for E4B. Cross-model transfer is free because the store is model-agnostic text.

### B.5 Staleness and versioning

- An app update (versionCode change) **marks** items `unverified_since_update` and **does not delete** them.
- After an update, routes run in guarded replay: any `expect_screen_sig` mismatch stops replay and falls back to the step loop. A route is invalidated after 2 signature failures or 1 end-oracle failure, as TARGET-PLAN §3.5 rung 6 already says.
- Tips decay: `hurt` increments when a tip was shown and the same failure code recurs. A tip retires when `hurt ≥ helped + 2`. Screen notes not seen for 90 days are dropped.
- Tree trust stays as in TARGET-PLAN §3.5.1: online, decaying, and it flips a screen class to pixel-first after repeated distrust.

### B.6 Privacy and safety rules

- **Location:** routes, screen notes and edges live in **actuator CE storage**, next to the Routes component. Only the actuator authors routes (TARGET-PLAN §3.5: "session can ask for a replay by id but cannot author steps"). App cards and tips live in the session's **Hints** store (§3.1: "per-app notes (low trust, editable)") and reach the model as quoted data with `origin: app_memory`.
- **No pixels at all.** Screenshots stay in the task record under its own rules (§3.8). AppMemory stores only roles, resource-ids, normalized control labels and bounds.
- **Secure windows:** for any node in a `FLAG_SECURE` window or a `secure_pixels` frame, store only `role + resource_id`, with no text and no bounds-derived content, and never in tips.
- **IME and notifications:** nodes inside the IME window, the notification shade or heads-up are never written. They are masked or stripped upstream too (§3.5.1).
- **Content text is not stored.** Only labels of controls (buttons, tabs, switch titles) are stored. Row content (messages, contacts, track titles, file names) is replaced with a placeholder like `<row title>`. Routes take content as typed parameters, never as recorded literals.
- **Injection resistance:** tips are written only from oracle-verified outcomes and the owner's edits, never from raw screen text. A tip cannot add an action type, an allowed package or a privilege. The actuator veto is evaluated after the memory, whatever the memory says.
- **Owner control:** view, edit and delete per app in VOS Settings. Delete-all is wired into the task-record deletion path (§3.8), and the store is purged on uninstall.

---

## C. Ranked ideas to adopt

Costs are for E4B on the Pixel 7. "Model call" ≈ 15.7 s at p50 today. Decode speed and the prefill/decode split are **not yet measured** (P0 below).

| # | Idea | Source | Fixes | On-device cost / latency | Effort |
|---|---|---|---|---|---|
| 1 | **`success_when` predicate checked by harness/actuator before every model call**; auto-finish when true; info tasks then get a text-only answer-extraction call | AndroidWorld state-based checks ([2405.14573](https://arxiv.org/html/2405.14573)); V-Droid makes "complete task" a default candidate ([2503.15937](https://arxiv.org/html/2503.15937v1)); VOS P3 | F2, F1, part of F3, F5 | Saves calls (stops early). The predicate check costs ~0 ms; extraction is 1 text-only call (no image, shorter prefill) | Low. A predicate DSL over observation v2: `switch(label)==on`, `text_visible(re)`, `title==`, `media_state==paused`, `field(label)` |
| 2 | **Observation trim** (remove duplicate `[i]` lines, container ids, mode-wrong instructions) | DIAGNOSIS §4 (80% of `[i]` lines duplicate, 32% of characters) | F7 | Negative cost: fewer prefill tokens | Low |
| 3 | **Constrained decoding**: JSON schema; `id` enum = current marks; `action` enum | LiteRT-LM: "Function calling support for agentic workflows with constrained decoding" ([overview](https://developers.google.com/edge/litert-lm/overview)); `enableConversationConstrainedDecoding` ([pub.dev](https://pub.dev/documentation/litertlm/latest/)) | F9, hallucinated ids | ~0. Fewer retries | Low–medium (Gemma 4 tool-call format differs per model) |
| 4 | **Checklist + progress note**: the harness shows plan items ✓/→; the model rewrites `progress` (≤15 words); the history shows state diffs | Mobile-Agent-v2 planning agent + memory unit ([2406.01014](https://arxiv.org/abs/2406.01014)); Mobile-Agent-E Notetaker ([2501.11733](https://arxiv.org/html/2501.11733)); M3A step summaries; OpenPhone text state assessment ([2510.22009](https://arxiv.org/html/2510.22009v2)) | F4, F5, F2 | +~20–30 output tokens/step (time = tokens ÷ measured decode tok/s); +~60 prompt tokens | Low |
| 5 | **`tap_seq` multi-action** validated per element against the live tree; stop at the first missing or disabled target | UFO2 speculative multi-action: check each control is still enabled and visible, stop at the first failure; steps −10% to −51.5% at similar success ([2504.14603](https://arxiv.org/html/2504.14603v2)); AutoDroid-V2 scripts ([2412.18116](https://arxiv.org/html/2412.18116v1)) | F4, F7 | Calculator 12×7: 5 calls → 1 (~60 s saved) | Low |
| 6 | **Stuck → bounded cloud escalation** (1–2 Grok steps, then back to E4B) | OpenPhone switching function ([2510.22009](https://arxiv.org/html/2510.22009v2)); MAI-UI device-cloud (+33% on-device, −40% cloud calls, abstract) ([2512.22047](https://arxiv.org/abs/2512.22047)); Mobile-Agent-E escalation after 2 consecutive failures | F5, F6, F1 | ~2.4 s model + network per escalation (Grok p50 today); redacted screenshot upload | Low in the harness (Grok backend exists) |
| 7 | **App cards + screen notes + tips** (B above) | DroidRun/Mobilerun app cards ([docs](https://docs.mobilerun.ai/framework/features/app-cards)); Mobile-Agent-E tips (GPT-4o 79.7%→87.5% on a tips-only subset); AutoDroid memory ([2308.15272](https://arxiv.org/abs/2308.15272)); AppAgent docs ([2312.13771](https://arxiv.org/abs/2312.13771)) | F6, F1 (Spotify semantics), F3 | +≤150 prompt tokens/step; ~ms lookup | Medium |
| 8 | **Semantic guards**: a tap on a toggle row, when the goal/plan names a target state, becomes `set_toggle` (no-op if already in that state). For media, read MediaSession before Play/Pause | Deft ([repo](https://github.com/bedda-tech/deft), exists; README not inspected for the toggle tool); TARGET-PLAN `ui.oracle` | F1, F2 | ~0 | Low |
| 9 | **Route replay / skill library** (rung 6), guarded by per-step screen signatures | SkillDroid (−49% LLM calls, 2.4× faster, abstract) ([2604.14872](https://arxiv.org/abs/2604.14872)); AppAgentX evolved shortcuts ([2503.02268](https://arxiv.org/abs/2503.02268)); MobileGPT sub-task reuse (−62.5% latency vs GPT-4 baseline) ([2312.03003](https://arxiv.org/abs/2312.03003)); EAM executable graph memory ([ICML 2026](https://icml.cc/virtual/2026/poster/65931)) | All, on repeat tasks; F7 most | No model calls per step: ~1–2 s/step on device | Medium |
| 10 | **Thinking only where it pays**: at plan time and on stuck L1, not every step | Ferret-UI Lite 3B: long CoT 19.6% vs short 15.8% on AndroidWorld, but "benefits remain limited" for inference-time techniques ([2509.26539](https://arxiv.org/html/2509.26539v1)) | F1, F4 (maybe) | 1,000 thinking tokens ÷ decode tok/s. Likely tens of seconds, so per-step use is probably unaffordable. The running test decides | Low (flag) |
| 11 | **Cloud critic only when stuck or before irreversible acts** (already `ui_verify`) | GUI-Critic-R1 pre-operative critic: 22.4→27.6 on AndroidWorld in M3A ([2506.04614](https://arxiv.org/html/2506.04614v2)); TARGET-PLAN §3.5.1 | F1, F6 | 1 cloud round trip when triggered | Low (exists in plan) |
| 12 | **Fine-tune from task records** (SFT on teacher steps; later a pairwise verifier) | AndroidControl: in-domain scales with data, out-of-domain slowly ([2406.03679](https://arxiv.org/abs/2406.03679)); V-Droid pairwise preference + entropy-flagged human labels; OpenPhone SFT→GRPO; GUI-Owl self-evolving trajectories ([2508.15144](https://arxiv.org/abs/2508.15144)) | All, long term | Off-device training. On-device cost unchanged | High. Whether a fine-tuned Gemma 4 can be converted back to `.litertlm` is **not verified** |
| 13 | **YOLO detector merged into the tree** for blind regions only, using UFO2's rule: drop visual boxes with IoU > 10% against any tree box | UFO2 hybrid detection ([2504.14603](https://arxiv.org/html/2504.14603v2)); TARGET-PLAN §3.2.1 | Popups with no ids (DIAGNOSIS), not E4B reasoning | Detector ≤120 ms p50 budget (§3.5.1) | Medium |
| 14 | **V-Droid-style on-device verifier** (score all candidates by first-token yes/no, prefill only, batched) | V-Droid: 8B verifier, 0.7 s decision on 2×RTX 4090, ~2.5 s estimated on NPU ([2503.15937](https://arxiv.org/html/2503.15937v1)) | F1, F2, F5 | Needs batched prefix-cached scoring of ~50 candidates. LiteRT-LM prefix/KV reuse is **not documented** on the pages I opened | High; research track |

**Not recommended on the phone now:**
- **Best-of-N + judge** (GTA1 ([2507.05791](https://arxiv.org/abs/2507.05791))): N separate E4B calls is ~N×15.7 s per step, unless the runtime can sample N continuations from one prefill, which is not verified. Keep it for the cloud tier.
- **The full Mobile-Agent-E 5-role loop per step**: manager, perceptor, operator, action reflector and notetaker, which is 4–5 calls, would be about 60–80 s of model time per step on E4B. Mobile-Agent-E itself ran ~27–30 s per operation on GPT-4o. Do the reflector's job deterministically with diffs and predicates.
- **Long thinking every step.** See row 10.

---

## D. Phased harness experiments (one change per phase)

**Task sets.**
- *Core-9* (memory-building and regression): `dark_on`, `dark_off`, `clock_timer_tab` (starting on the Alarm tab), `calc_12_times_7` (cap ≥10), `about_android`, `spotify_pause`, `open_display`, `battery_level`, `bluetooth_off`.
- *Held-out-6* (memory phases only; never used to write tips): `night_light_on`, `screen_timeout_2m`, `font_scale_115`, `settings_search_battery_saver`, `calc_45_plus_55_div_4` (cap ≥14), `spotify_resume`.

Memory must be judged on held-out tasks in the same apps, because AndroidControl shows in-domain gains don't carry to new tasks for free. Run 2 repeats on E4B (greedy, mode C). Grok runs once per phase as the ceiling only when the change touches the prompt. One E4B Core-9 pass is about 9 × ~6 steps × 24 s ≈ 22 min.

**Metrics recorded every phase:**
- **Strict pass:** check false before step 1, true at the end, `done` declared, and the answer is correct for info tasks.
- **System pass:** the same, but the end may come from `success_when` instead of `done`. Report both: model-only competence vs the shipped workflow.
- **Lucky:** check true without `done`.
- **Done detection:** the harness silently runs the check every step, giving g = first step where the goal holds and d = step where done was declared or the system stopped. Report:
  - done precision (done only when the goal holds);
  - done recall within 1 step of g;
  - overshoot rate (goal held at g, then was lost);
  - predicate false-stop rate (system stopped while the check was false; must be 0).
- **Loop rate:** share of steps with stuck level ≥1, and repeated no-effect actions per task.
- **Speed:** steps to success; wall-clock per task; s/step p50/p90 split into prefill, decode and harness; prompt and output tokens per step; invalid-output rate.
- **Escalation and memory:** cloud calls and upload bytes per task; memory tokens injected; route hits.

| Phase | Single change | Expect | Primary metric | Keep if |
|---|---|---|---|---|
| **P0** | Instrumentation only: per-step silent check, prefill/decode timing, decode tok/s; finish the running thinking test (budget 0 vs ~1,000) | Baseline | All of the above | n/a. Sets the latency model for later phases |
| **P1** | Observation trim (DIAGNOSIS §4) | Lower prefill, same pass | s/step, prompt tokens | Pass not lower; s/step lower |
| **P2** | Constrained decoding (schema + id enum) | Invalid output → 0 | Invalid rate, F9 count | Invalid drops; pass not lower |
| **P3a** | `success_when` hand-written per task (upper bound) + auto-finish + text-only answer extraction | Fixes F2/F3 | System pass, done recall, overshoot, false-stop | False-stop = 0; overshoot drops |
| **P3b** | Same, but `success_when` written by one Grok text-only plan call | Shows whether the planner can write predicates | Predicate accuracy vs ground-truth check; system pass | Within 1 task of P3a |
| **P4** | Checklist + `progress` note field | Fixes F4/F5 | Strict pass, loop rate, steps | Loop rate drops; s/step rise ≤ 3 s |
| **P5** | `tap_seq` | Calculator in 1–2 calls | Calc pass, calls per task | Calc passes; no new misfires |
| **P6** | Hand-written app cards for Settings, Clock, Calculator, Spotify (≤10 bullets each) | Fixes F6/F1 semantics | Strict pass on Held-out-6 | Held-out pass ≥ +1 task |
| **P7** | Auto tips: reflector over Core-9 failure records, candidates promoted by the gate; evaluate on Held-out-6 | Learned memory ≈ hand-written | Held-out pass vs P6, tips added/retired | Within 1 task of P6 |
| **P8** | Stuck L2 → 1 Grok step, then back to E4B (max 2 per task) | Fixes F5/F6 | Pass, cloud calls/task, bytes up | Pass up with ≤1.5 cloud calls/task |
| **P9** | Route replay on a second run of passed tasks, plus perturbed starts (different start page, toggles pre-flipped) | Repeat tasks at replay speed | Wall-clock/task, replay success, mismatch fallback rate | Replay pass ≥ step-loop pass; ≥5× faster |
| **P10** | Thinking budget only at plan time and stuck L1 (if P0 shows per-step thinking is too slow) | Better plans at bounded cost | Pass, s/task | Pass up without s/task doubling |

Small n (9 tasks × 2) means a difference of one task is noise. Look at the failure codes (F1–F9) per phase, not just the totals. Also re-check the LMK issue (F8) during Spotify phases and log whether the model service survived.

---

## E. TARGET-PLAN (OS design) vs harness only

| Idea | Where | Why |
|---|---|---|
| `AppMemory` component (keys, item types, privacy rules, owner view/edit/delete), split between actuator (routes, screen notes, edges) and session Hints (cards, tips) | **TARGET-PLAN** §3.5 rung 6 + §3.1 Hints + §5.8 | Persistent OS state with privacy and injection implications |
| `success_when` in the Conductor plan and the task record; actuator evaluates it; `VERIFIED` only from actuator oracle types, `SATISFIED` for observation predicates | **TARGET-PLAN** §3.5, §3.8 header, P3 | Changes what "done" means at OS level |
| ModelRouter class `ui_step` (on-device LLM executor) with explicit escalation triggers (stuck L2, 2 no-progress steps, 2 invalid) to `vision_step`/`plan_long`, max escalations per task, audited | **TARGET-PLAN** §3.2.1 | Today there is `control_detect` (on device) and `vision_step` (cloud) but no on-device LLM step role |
| Actuator tools `ui.set_toggle(target,state)` (idempotent) and `ui.tap_seq(locators[])` (per-element validation, stop on first miss) | **TARGET-PLAN** §3.5.2 | Hands-level tools; idempotence is also a safety property |
| Stuck detector signals (repeat, no-change, oscillation) emitted by the actuator's screen-diff oracle; the **decision** to escalate or stop stays with the Conductor | **TARGET-PLAN** §3.7, decision 22 | The actuator supplies the facts; it does not take over the plan |
| Model residency for the on-device executor (LMK kill when Spotify opened) | **TARGET-PLAN** spike (Tier P/F priority for the model service) | OS-level memory policy |
| Training-data export from task records (step labels, teacher corrections, pairwise pairs) | **TARGET-PLAN** §3.8 consumers (already allows owner-approved export) | Already in scope; add the step-label fields |
| Prompt wording, letter marks, observation format, history length, thinking budgets, hand-written app cards for test apps, Grok-as-teacher runs, silent per-step check, P0–P10 | **Harness only** | Eval and tuning detail that will change often |
| Best-of-N / on-device verifier research | **Harness only** (research track) | Not shippable at 24 s/step |

---

## F. Sources (opened during this run)

Opened with WebFetch:
- Mobile-Agent-E: https://arxiv.org/abs/2501.11733 and https://arxiv.org/html/2501.11733
- Mobile-Agent-v3 / GUI-Owl: https://arxiv.org/abs/2508.15144
- Mobile-Agent-v2: https://arxiv.org/abs/2406.01014
- AppAgent: https://arxiv.org/abs/2312.13771
- AppAgentX: https://arxiv.org/abs/2503.02268
- MobileGPT: https://arxiv.org/abs/2312.03003
- AutoDroid: https://arxiv.org/abs/2308.15272
- AutoDroid-V2: https://arxiv.org/html/2412.18116v1
- Agent S: https://arxiv.org/abs/2410.08164
- Agent S2: https://arxiv.org/abs/2504.00906
- UFO2: https://arxiv.org/abs/2504.14603 and https://arxiv.org/html/2504.14603v2
- AndroidWorld (M3A): https://arxiv.org/html/2405.14573
- UI-TARS: https://arxiv.org/abs/2501.12326
- V-Droid: https://arxiv.org/html/2503.15937v1
- OpenPhone (LightAgent): https://arxiv.org/html/2510.22009v2
- MAI-UI: https://arxiv.org/abs/2512.22047
- Ferret-UI Lite: https://arxiv.org/html/2509.26539v1
- GUI-Critic-R1: https://arxiv.org/html/2506.04614v2
- GTA1: https://arxiv.org/abs/2507.05791
- Agent Workflow Memory: https://arxiv.org/abs/2409.07429
- Autonomous evaluators for digital agents: https://arxiv.org/abs/2404.06474
- AndroidControl: https://arxiv.org/abs/2406.03679
- SkillDroid: https://arxiv.org/abs/2604.14872
- EchoTrail-GUI: https://arxiv.org/abs/2512.19396v1
- Executable Agentic Memory (EAM): https://icml.cc/virtual/2026/poster/65931
- PokeClaw: https://github.com/agents-io/PokeClaw
- DroidRun / Mobilerun: https://github.com/droidrun/droidrun
- Mobilerun app cards: https://docs.mobilerun.ai/framework/features/app-cards
- LiteRT-LM overview: https://developers.google.com/edge/litert-lm/overview
- LiteRT-LM repo: https://github.com/google-ai-edge/LiteRT-LM
- litertlm Dart package docs: https://pub.dev/documentation/litertlm/latest/

**Not verified:**
- "Deft": the repo https://github.com/bedda-tech/deft exists (Gemma 4 on-device Android agent, checked by Grok Bot after the run); its set_toggle details were not re-read here.
- PokeClaw's class name `StuckDetector`: its README describes the five-signal detector without that name.
- T3A details: not in the AndroidWorld text I read.
- Agent S narrative/episodic memory details: abstract only.
- LiteRT-LM prefix/KV reuse and thinking mode: not on the pages opened.
- Whether a fine-tuned Gemma 4 can be converted to `.litertlm`.

Several of the papers were read only as abstracts (marked "abstract" above). Their mechanisms are described only as far as the abstract states.


---
_Post-run check (Grok Bot, 2026-10-17 ~2:10 PM CDT): 7 cited URLs return HTTP 200; SkillDroid (49% fewer LLM calls, 2.4×), MAI-UI (+33% on-device, >40% fewer cloud calls) and OpenPhone (~65% cloud steps, ~10% fewer cloud calls) figures match the sources. Two self-corrections Claude flagged and the Deft note were applied above._
