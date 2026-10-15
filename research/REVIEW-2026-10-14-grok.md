I'll read `docs/TARGET-PLAN.md` and related spike/test references so the review matches the new Tier F rules.The plan is large; I’ll pull the privacy, Tier F, injection, and owner-touch sections next.I’ll grep remaining stale FLAG_SECURE, INJECT_EVENTS, patch-cap, and trigger-gated wording so the line citations are exact.# Independent review: `docs/TARGET-PLAN.md` after 2026-10-14

Read-only. No files edited, no git.

The 2026-10-14 decisions (F5 capture, actuator-only F7, no patch-count cap, Tier F committed after M4) are already in §4.6, decision 4, row 5, S-35, and M4-INJ-01. The security model, privacy text, P10, and several test/spike rows still describe the old world.

---

## 1. Threat model: F5 + F7 vs session/actuator split

The UID split still holds: session has network and no sensors/actuators; actuator has sensors/actuators and no `INTERNET`; Binder is the only bridge (P2). F5 and F7 do **not** give the session pixels or injection. They make the **actuator** a full compositor observer and a real input source. The residual risk is what the actuator is willing to **release** to the session (and thus to vendors), and whether F7 events can **impersonate the owner** inside the actuator.

### What changed

| Capability | Before | After |
|---|---|---|
| Secure windows | Black / tree wireframe; not a screenshot class | Actuator `takeScreenshot` includes `FLAG_SECURE` layers (F5). Session/cloud get whatever the actuator hands over. |
| Input | `dispatchGesture` / `ACTION_SET_TEXT` (a11y, apps can ignore) | Real `InputManager.injectInputEvent` for actuator UID only (F7). Same trusted-path gates on paper. |
| Presence (b) “hands” | S-09 vs `dispatchGesture` | F7 events enter InputDispatcher like finger events unless tagged and filtered. |

### Concrete attack paths

**A. Prompt injection → banking pixels to the vendor (A1 + A4).** Hostile notification/page → session `ui_task` → Operator `screen.capture` on a `FLAG_SECURE` activity (bank, password manager, 2FA) → Binder PNG → `vision_step` / `ui_verify`. Lines 587 and 634 say upload “like any other screen.” P12 and the UID split do not stop this: the actuator has no network, but it **releases** the frame. Mitigation: treat F5 as **local capture**. Cloud still IME-masks; `source: secure_pixels` leaves the phone only on an owner policy (default deny) or stays on-device (`control_detect` / wireframe). Audit `secure_pixels` as its own egress class.

**B. Prompt injection → real tap in an a11y-blocking app (A1).** F7 is why injection now works where `dispatchGesture` failed. Session still cannot call `injectInputEvent`; it can only send a goal. Operator must still classify, announce, guard, and require `owner_turn`. Residual: non-catastrophic irreversible acts (SMS, share) dispatch after 1.5 s of silence if **presence is true**. If F7 counts as hands (path D), the room can be empty and the act still fires. Mitigation: F7 never sets presence (b); catastrophic classes still need spoken “yes”; injection refused unless origin is `owner_turn` **and** the act is actuator-classified (already in F7 row, line 870).

**C. Session RCE (A5).** Unchanged in kind: session can only call the authenticated actuator API. F7’s “never from the session or model-supplied code” is already implied by P2. Do not add a Binder `inject(MotionEvent)` — keep `ui.gesture` / `input.inject` as actuator-internal, args as intent (`tap(mark)`, `scroll(toward=end)`), never raw events the model authors.

**D. F7 forges presence (b) (A1 + A3).** Presence (b) is “human touched in the last 60 s” via S-09 (lines 1017–1018). `INJECT_EVENTS` joins InputDispatcher with `POLICY_FLAG_INJECTED` and typically `SOURCE_TOUCHSCREEN`. `setObservedMotionEventSources(SOURCE_TOUCHSCREEN)` will likely **see** F7 events (`dispatchGesture` often does not). Then: fake 60 s hands → irreversible acts not `DEFERRED` → also poisons takeover (M3-UI-03) if the agent’s own tap looks like the owner. `grantTrust` is only presence (a) (line 1029), so Extend Unlock is safe from this. Mitigation: presence (b) = physical EventHub device, not injected policy flags (see §2).

**E. Compromised actuator process (A2 elevated).** Actuator UID with F5+F7 can read every layer and tap anything. Still no `INTERNET`. Exfil = Binder → session → vendor. Mitigation: same as A; plus F2 hardware chord the actuator cannot intercept; volume-key a11y kill (S-11); no platform signature on the actuator (keep).

**F. Other UID calls `injectInputEvent` (A2).** S-35 already requires `SecurityException` for session and third-party UIDs. Keep `INJECT_EVENTS` off the privapp XML for anyone but `com.vos.actuator`. Do not grant it via `signature|privileged` to a shared cert.

**G. Theft of an unlocked phone (A7).** F5 means a thief with an unlocked, present phone (headset ACL) gets agent-visible banking. Same as a thief who can look at the screen, plus cloud if egress is open. Mitigation: presence (a) for private reads on lock; F5 capture while locked = no; cloud `secure_pixels` default off.

**H. uinput / kernel impersonation.** Out of scope if F7 stays in `InputManagerService`. A `/dev/uinput` or EventHub path would be indistinguishable from a finger. Do not add it.

The split is still the right control. What broke is the old assumption that **secure pixels never cross Binder** and **injected input cannot look like a finger**. Write those as invariants in §5.1 as new rows, not as FLAG_SECURE / INJECT_EVENTS bans.

---

## 2. Can F7 / `dispatchGesture` fool S-09?

**`dispatchGesture`:** S-09’s pass criterion (“injected gestures not reported”) is the intended a11y path. Usually those events carry `FLAG_INJECTED_FROM_ACCESSIBILITY` and do **not** show up as owner motion. Keep S-09 as the `dispatchGesture` witness.

**F7 `InputManager.injectInputEvent`:** Yes, it can fool a source-only “hands” check. Injected events skip EventHub/InputReader, enter InputDispatcher, and are delivered to windows as `SOURCE_TOUCHSCREEN` if the injector sets that source (required for apps that ignore a11y). `setObservedMotionEventSources(SOURCE_TOUCHSCREEN)` will treat them as real unless F7 tags them **and** S-09 filters the tag.

The plan already wants a “VOS policy flag” (lines 693, 870, 1071) but does not define it, and S-09 (line 1070) still only mentions `dispatchGesture`.

### How to tag (use all of these; do not rely on one)

| Signal | Where | Rule |
|---|---|---|
| Policy flags | `InputManagerService.injectInputEventInternal` | Always set `POLICY_FLAG_INJECTED`. Add `POLICY_FLAG_INJECTED_FROM_ACCESSIBILITY` **or** a VOS bit (`POLICY_FLAG_VOS_INJECTED`) that S-09 and presence read. Apps should not see this. |
| Virtual `deviceId` | Injector | Dedicated virtual `InputDevice` (`vos-inject`). **Never** copy the panel’s `deviceId` (trivial impersonation). |
| `MotionEvent` source / tool | Injector | Source may be `SOURCE_TOUCHSCREEN` so target apps accept it. Tool type `TOOL_TYPE_UNKNOWN` or a documented VOS tool if the app still accepts the tap. Source is **not** a security boundary. |
| Kernel path | Presence (b) | Owner touch = EventHub/InputReader device with `InputDevice.isExternal()==false` and vendor/product of the built-in panel. Injected = no EventHub node. |
| Timing / axes | Heuristic only | Synthetic pressure=1, size=0, zero jitter. Useful in tests, not a control. |
| Actuator ledger | Operator | Every F7/dispatchGesture enqueue is logged with `t_ns`, `deviceId`, `policyFlags`, `turnId`. Presence (b) ignores motion that matches an in-flight inject window. |

Presence (b) definition should become: unlocked + interactive + **physical** touch in 60 s, **excluding** VOS-flagged / injected / virtual-device events. Takeover (M3-UI-03) uses the same predicate: physical finger preempts inject; inject does not preempt itself.

### Tests to add (or tighten)

S-09 today is M3 and `dispatchGesture`-only. S-35/M4-INJ-01 sit in M4 while F7 is post-M4. Split as:

- **S-09 (keep, M3):** physical finger vs `dispatchGesture`. Pass: finger ≤100 ms; gesture not reported as hands.
- **S-35 (move to Tier F milestone, not M4):** F7 on a fork build. Pass already listed, plus: VOS-flagged events **never** set presence (b); `deviceId` is the virtual device, never the panel.
- **M4-INJ-01:** keep the trusted-path/session-refuse clauses; add the presence clauses below, and run it on the fork (`F-PATCH-F7`), not as an M4-EXIT gate.
- **M3-UI-03b / F-PATCH-F7-PRESENCE:** inject tap/swipe/key while idle → presence (b) stays false; 60 s window does not start; `grantTrust` unchanged.
- **F-PATCH-F7-TAKEOVER:** physical finger during F7 inject → inject stops ≤300 ms (same as M3-UI-03); F7 does not fire takeover against itself.
- **F-PATCH-F7-IMPERSONATE:** attempt inject with panel `deviceId`, `SOURCE_TOUCHSCREEN`, no VOS flag → **refused** in IMS.
- **F-PATCH-F7-UID:** session UID, a third-party app, and a same-cert non-actuator package all get `SecurityException` (S-35 already).
- **F-PATCH-F7-LEDGER:** every inject in the audit log with flag/deviceId; 0 unlogged events in a 1k-event soak.

---

## 3. Stale lines vs the new rules

Replacement text is exact. Hatch L “triggers” and F3/F4 “only if Caleb asks” are still correct; those are not listed.

### Privacy / redaction (contradicts F5-as-capture, and over-uploads)

| Line | Stale | Replace with |
|---|---|---|
| **291** | `Cloud vision_step and ui_verify run only in the session on **redacted** screenshots (IME/secure masked; §3.5.1).` | `Cloud vision_step and ui_verify run only in the session on **redacted** screenshots (IME always masked; notification shade stripped unless the task is about notifications; `source: secure_pixels` uploaded only if owner policy allows, default deny — local F5 capture is not cloud egress; §3.5.1, §5.8).` |
| **569** | `redact: mask IME region, strip notification contents` | `redact: mask IME region, strip notification contents if policy forbids; drop or locally-only `secure_pixels` unless owner policy allows cloud` |
| **617** | `**Privacy:** secure-window frames included like any other; password, payment and message-content regions redacted (or the frame skipped).` | `**Privacy:** F5 may capture secure-window frames for the actuator. Training/cloud: IME always masked; password, payment, and message-content regions redacted (or the frame skipped); `secure_pixels` leave the device only on owner opt-in.` |
| **634** | `**Privacy.** IME region always masked before upload. \`FLAG_SECURE\` windows are uploaded like any other screen (audited per P12; real pixels need F5).` | `**Privacy.** IME region always masked before upload. Local capture of \`FLAG_SECURE\` windows is allowed (F5). Upload of those pixels is a separate P12 decision (default: do not upload; wireframe/marks only). Every upload: audit \`(hash, bytes, destination, turnId, secure: bool)\`.` |
| **1040** | `\`FLAG_SECURE\` windows are sent like any other screen (F5).` | `\`FLAG_SECURE\` windows are captured locally like any other screen (F5). They are sent to the session/cloud only under owner policy (default: wireframe/marks, not pixels).` |
| **587** | `\`FLAG_SECURE\` windows are **captured and sent like any other screen** (Caleb: no special secure-screen class).` | `\`FLAG_SECURE\` windows are **captured like any other screen** for the actuator (Caleb: no special secure-screen class). **Sent** to session/cloud per §5.8 (default: local pixels + wireframe/marks over Binder; no vendor upload).` |
| **1189** | `Every cloud image audited; \`FLAG_SECURE\` uploads audited like any other; IME region masked` | `Every cloud image audited; IME masked; \`secure_pixels\` absent from uploads unless policy allows (then audited with \`secure: true\`)` |

Line 632 (“skip cloud when entirely FLAG_SECURE and F5 is absent”) is still right for Tier S/P.

### Patch cap, trigger-gating, 3-week estimate, changelog

| Line | Stale | Replace with |
|---|---|---|
| **4** (header Date) | `2026-10-12 (... dual-device panther/akita)` | `2026-10-14 (Tier F committed after M4; F5 secure-layer capture; F7 actuator INJECT_EVENTS; no patch-count cap; plus 2026-10-12 items)` |
| **17** | `It is used only when a Shell Contract row (§3.6) needs it.` | `It is the shipping build, started right after M4. Patches are named, small, and rebased per Graphene tag; F5 and F7 are unconditional; further patches join when a Shell Contract row needs them (§4.6).` |
| **81** (changelog gap) | Last table rows are 2026-10-12 only. No 2026-10-14 row next to this block. | Add four rows after line 91 (exact text below). |
| **102** | `Tier F is capped at 5 named patches, each tied to a Shell Contract row (§4.6).` | `Tier P has zero patches. Tier F is a named queue with no count cap; each patch is small, self-contained, tied to a Shell Contract row, tested, and rebased per Graphene tag (§4.6).` |
| **161** | `Tier F is capped at 5 named patches, each tied to a Shell Contract row (§4.6).` | `A broad, unscoped fork is still a non-goal. Tier F is a named, rebased patch queue with no count cap; each patch stays small and contract-tied (§4.6).` |
| **192 (P10)** | `Tier F patches exist only for named Shell Contract rows: at most 5, each with a test and a rebase check.` | `Tier F patches exist only for named Shell Contract rows; no count cap; each has a test and a rebase check. F5 and F7 are committed.` |
| **687** | `Tier F patch (only if needed)` | `Tier F patch` |
| **754** | `No \`signature\`-only permission is needed.` | `The only \`signature\`-only permission is \`INJECT_EVENTS\`, granted to the \`com.vos.actuator\` UID by F7, without platform-signing the actuator. \`READ_FRAME_BUFFER\` / \`CAPTURE_SECURE_VIDEO_OUTPUT\` stay rejected.` |
| **878** | `About 3 weeks to first boot.` | `First-boot cost is measured at fork start (post-M4); do not treat “3 weeks” as a gate. Disk ~250 GB; 2–4 h full build; ~1–2 days/month rebase+soak.` |
| **1214** | `Tier F go/no-go (including F6 if duplex blocked).` | `Tier F starts after M4 (committed). Record extra patches (F1/F2/F6, Q2/Q15) in \`docs/DECISIONS.md\` (M4-F-DECIDE).` |
| **1246** | `**Tier F milestone (committed; starts right after M4, about 3 weeks, before or in parallel with M5):**` | `**Tier F milestone (committed; starts right after M4, before or in parallel with M5):**` |
| **1332** | `If Tier F triggers, grow the file to 600 GB` | `Before the post-M4 fork sync, grow the file to 600 GB` |
| **1359** | `graphene/      ONLY if Tier F triggers:` | `graphene/      post-M4 (committed):` |
| **8 / 909** | Tier P as “full-power reference”; Phone A “moves to the VOS Image at M4-EXIT” | Tier P = first dev image / fallback; shipping = Tier F. Phone A: VOS Image at M4-EXIT, Fork when the post-M4 fork soaks. |

**Changelog rows to insert after line 91:**

```text
| (2026-10-14) Drop FLAG_SECURE non-goal; capture secure screens like any other | Decision 4 + §3.5.1 F5 + non-goal strike at line 164. Local capture via Tier F F5. Cloud egress of those pixels remains a P12 policy (default deny). |
| (2026-10-14) Allow actuator-only INJECT_EVENTS | Shell row 5 F7; spike S-35; test M4-INJ-01 moved to F-PATCH-F7. Session/other UIDs still cannot inject. |
| (2026-10-14) Remove the Tier F patch-count cap | P10, §4.6, non-goal “broad fork”: named queue, no ≤5 cap. |
| (2026-10-14) Commit to Tier F right after M4 | Q16 answered. F5 and F7 unconditional. Additional patches still contract-gated. |
```

### Remaining FLAG_SECURE / INJECT_EVENTS ban language

- **163** is already updated (INJECT_EVENTS removed from the non-goal). Keep the platform-signature ban.
- **164** strike is correct as a non-goal removal. Pair it with the capture-vs-upload split above so “screens like any other” does not mean “vendors see banking.”
- **216–218** Hands box lists `dispatchGesture` only — add `InputManager.injectInputEvent` (F7).
- **931** actuator “Holds” column: add `INJECT_EVENTS` on Tier F (F7). Still never holds a platform signature.
- **1070 S-09** still bans only `dispatchGesture` from the hands check — extend the claim to F7-flagged events (or point S-09 at dispatchGesture and S-35 at F7).
- **543 / 563** still say gestures via `dispatchGesture` only — mention F7 fallback.

Trigger-gated wording that **should stay:** Hatch L (§4.7), F3/F4 (Q2/Q15), F6 “only if A–C fail,” line 30 Hatch L, line 1360 Lineage.

---

## 4. Top 5 concrete fixes (priority)

1. **Split F5 capture from F5 egress in §3.5.1, §5.8, P12, M3-PRIV-01.** Owner asked for the agent to *see* secure screens. That does not authorize xAI to see them. Default: local pixels; Binder marks/wireframe; no `secure_pixels` upload. This is the highest-severity hole the 10-14 edit opened.

2. **Specify the F7 tag and rewrite presence (b).** Policy flag + virtual `deviceId` + EventHub-only hands. Ban injecting the panel `deviceId`. S-09 stays the `dispatchGesture` spike; S-35/F-PATCH-F7 prove F7 cannot set presence or self-preempt takeover. Without this, trusted-path defer is bypassable.

3. **Move F5/F7 tests off the M4 critical path.** S-35 and M4-INJ-01 require a fork image; Q16 starts that fork *after* M4. Gate M4-EXIT on Tier P. Gate F-BUILD-01 / F-PATCH-F5 / F-PATCH-F7 on the fork. M3-VERIFY-02 “with F5” is a fork test; without F5 stays M3.

4. **Delete the cap and trigger-gated leftovers in one pass:** lines 17, 102, 161, 192 (P10), 687, 754, 1214, 1332, 1359. Insert the four 2026-10-14 changelog rows at the feedback table (~81–91). Drop “about 3 weeks” as a schedule number (878, 1246).

5. **Align permissions and process map with F7:** §4.1 item 3 (`INJECT_EVENTS` is the one signature-only grant); §5.2 Holds column; Hands in §3.1; `ui.gesture` already mentions F7 — make IMS the documented fallback, never a Binder raw-event API.

**Residual (not in top 5):** header date; decision 8 still calling Tier P the full-power build; Phone A M4-EXIT → Image vs Fork; training-log line 617 vs 634.
