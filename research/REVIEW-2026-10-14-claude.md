I've read all of `docs/TARGET-PLAN.md` and here is the review. I didn't edit any files or run git.

# Review: TARGET-PLAN.md after the 2026-10-14 changes

## 1. Threat model after F5 secure capture and F7 injection

**The main problem.** The split between the session and the actuator used to protect two things:
- **Integrity:** only the offline actuator can act.
- **Confidentiality:** "sensors stay on the side with no network" (L260).

F5 now routinely sends the phone's most sensitive pixels (banking, password managers, 2FA apps) to the networked session. Integrity still holds. Confidentiality now depends on rules the plan hasn't written yet. The threat table (§5.1, L917–925) has no row for either change.

| # | Attack path | Existing control | Gap | Mitigation |
|---|---|---|---|---|
| T1 | Prompt injection (A1): a hostile page tells the model to "open the authenticator/bank app and read it". F5 captures it, it goes to the session, then to a vendor. | P12 upload audit, declared hosts | The declared hosts are themselves the leak path: the vision/voice vendor, plus remote-MCP `read` tools (L521), whose arguments can carry data. | Release secure pixels only for an Operator step the actuator started, in an `owner_turn` whose task app matches the secure window's package. Never for `goal:`, `schedule:` or `none` origins. Mark the turn `secure_taint`, which blocks all non-vision egress (MCP, RSS) for the rest of the turn. |
| T2 | A session compromised through remote code execution (A5) uses an open turn to request captures in a loop. | Binder auth, `turnId` | L923 ("can only call the actuator API") no longer limits what data can leak. | Give the session no way to pull captures: the Operator pushes frames only for the current step. Cap secure captures per turn. The house voice or indicator says "looking at <app>" the first time per turn. |
| T3 | F7 events count as owner touch, so presence (b) is satisfied by the actuator itself. Irreversible acts then dispatch with no owner (§5.5 step 5). | S-09 only rules out `dispatchGesture` | See §2 | Count presence only from hardware touches. |
| T4 | F7 reaches surfaces that accessibility can't, or that apps block on purpose: finance apps marked `accessibilityDataSensitive`, the keyguard PIN screen (bouncer), PermissionController, Settings security pages, VOS's own stop/Settings UI. | Trusted-path hit-test (L993) | Where accessibility is blocked the node tree is thin, so commit detection falls back to OCR (the residual risk at L1001). | Enforce a window denylist in both the actuator and the patch. Add a keycode denylist (power, the volume-chord keys). Any app driven through F7 because accessibility is blocked uses announce-every-commit mode. |
| T5 | The actuator is compromised through hostile pixels or text (OCR, detector, image decoding). | hardened_malloc / MTE | The actuator now holds `INJECT_EVENTS` and secure capture, plus a Binder pipe to a networked process. A compromise means full control of the device plus a way to leak data. | Run OCR, the detector and image decoders in `isolatedProcess` children. Keep the F5/F7 callers in a small core. |
| T6 | Stop paths | P5, F2 | F2 suspends only "gesture dispatch" (L706), not F7. | Spoken stop, the QS tile and F2 also suspend F7. Stop detection reads hardware key events only. |
| T7 | Secure frames stored on the phone: training records (L617), scratchpad, export to the desktop | Stored on-device by default | Not specified | Keep `source: secure_pixels` frames out of the training store by default, with a separate opt-in for export. |
| T8 | VTB peers get `screen.capture` (L679) | "redacted" | Doesn't mention secure pixels | Never send `secure_pixels` to peers, and limit each peer to its own windows. |
| T9 | F5/F7 reach beyond the actuator | "nothing else gains access" (L868) | No test | Add F-PATCH tests: other UIDs, MediaProjection, `screencap` and other accessibility services still get black frames or `SecurityException`. |

## 2. Can injected events pass as owner touch (S-09)?

**Assume they can until S-35 shows otherwise.** S-09 (L1070) only checks `dispatchGesture`. Two other sources look like touches:
- **F7 events.** They must stay `SOURCE_TOUCHSCREEN` or apps reject them, and they go through the same dispatch path that accessibility observes.
- **Virtual touchscreens.** `CREATE_VIRTUAL_DEVICE` touchscreens (duplex Path C, L382) are a second source.

This can fail two ways:
- **False presence:** irreversible acts run without the owner (T3).
- **False takeover:** M3-UI-03 aborts tasks because it sees VOS's own injections.

**How to tell them apart, strongest signal first:**
1. **Input path (primary).** Real touches enter through InputReader from the built-in panel's kernel device; injected events skip InputReader. F7 should stamp each event's origin in the framework, where apps can't forge it, and let the actuator read it. Presence counts only events from the built-in touchscreen on the default display.
2. **deviceId.** F7 sends all VOS injection through a dedicated VOS virtual input device. Presence checks against an allowlist holding the panel's device id, recorded at boot, rather than `source`. This also excludes Path C virtual devices and external keyboards or mice.
3. **Policy flags.** The platform already marks injected events, and F7 adds a VOS flag. Use them for audit and cross-checks. Rely on them alone only if S-35 confirms the accessibility observer can see them.
4. **Timing (secondary).** The actuator logs every injection (sequence number, time, coordinates). An observed event that matches the log is VOS's own, which is useful for avoiding false takeovers. It is never proof of a human.
5. **Hardening.** Test that no VOS UID can create a kernel input device.

**Tests to add:**
- **Extend S-09/S-35:** compare a real finger, `dispatchGesture`, F7 touch/key, a VirtualDevice touchscreen, and the dev-only shell `input`. Pass: only the finger counts (≤100 ms); the others score 0/200.
- **M2-PRES-01:** phone unlocked, headset off, no human touch for 60 s, F7 active while an irreversible fixture runs. Pass: `DEFERRED`, 0 dispatches.
- **M4-INJ-02:** 100 F7 UI steps with no human touch should give 0 false takeovers. Then touch with a real finger mid-burst. Pass: injection stops in ≤300 ms.
- **M4-INJ-03:** inject into the denylisted windows and keycodes. Pass: refused and audited. Then trigger F2 or the QS stop during an injection burst. Pass: F7 suspended in ≤300 ms.
- **Audit:** every presence decision records where the satisfying event came from.

## 3. Stale lines

I found no remaining `FLAG_SECURE` or `INJECT_EVENTS` bans except L755. L163–164 are already updated.

| Line | Replace | With |
|---|---|---|
| 5 | `2026-10-12 (wake-word AEC configurable; …` | `2026-10-14 (Tier F committed, starts after M4, no patch cap; F5 secure-screen capture; F7 actuator-only INJECT_EVENTS; 10-13: control_detect v0 = a11y-tree adapter + ui_verify; 10-12: wake-word AEC configurable; …` |
| 17 | `with a short, named patch queue. It is used only when a Shell Contract row (§3.6) needs it.` | `with a named patch queue (no count cap). It is the committed shipping build, started right after M4 (Caleb, 2026-10-14); every patch maps to a Shell Contract row (§3.6, §4.6).` |
| 27 | `**Tier P (default):**` | `**Tier P (first privileged build, M4; fallback once Tier F ships):**` |
| 35 | `Tier P is the full-power reference build.` | `Tier P is the first privileged build (M4); Tier F is the full-power reference build (F5 and F7 exist only there).` |
| 81 | `Leave concrete \`control_detect\` model **open**. … M3 binds whatever wins.` | `**Resolved 2026-10-13:** \`control_detect\` v0 = a11y-tree adapter (decision 11, §3.2.1); small permissive detector (YOLOX / MIT YOLO, not Ultralytics AGPL) only for blind regions after S-UI-TREE + S-UI-LAT; \`ui_verify\` SoM verify (§3.5.1).` |
| after 91 | (no 2026-10-14 feedback rows yet) | Add 3 rows: `(2026-10-14) Secure screens are screens like any other` → `FLAG_SECURE non-goal dropped; F5; wireframe until F5; M3-VERIFY-02; release policy §5.8`. `(2026-10-14) Allow real input injection` → `F7 actuator-only INJECT_EVENTS, no platform signature; row 5; S-35; M4-INJ-01`. `(2026-10-14) It's OK to fork; no cap; start after M4` → `Decision 4; §4.6; Q16 answered`. |
| 102 | `Tier F is capped at 5 named patches, each tied to a Shell Contract row (§4.6).` | `Tier F has no count cap; each patch is small, named, row-tied, tested (F-PATCH-nn) and rebased per tag; rebase cost feeds Hatch L trigger 2 (§4.6–4.7).` |
| 161 | same sentence as L102 | `Tier F has no count cap; rebase cost is the limit: each patch small, named, row-tied, tested; >1 day rebase twice a quarter trips Hatch L (§4.6–4.7).` |
| 192 | `at most 5, each with a test and a rebase check.` | `no count cap; each small, with a test and a rebase check.` |
| 291 | `(IME/secure masked; §3.5.1)` | `(IME masked; FLAG_SECURE sent like any other screen under the F5 release policy; §3.5.1, §5.8)` |
| 679 | `(\`screen.capture\` redacted,` | `(\`screen.capture\` redacted, never \`secure_pixels\`, scoped to the peer's own windows,` |
| 687 | `Tier F patch (only if needed)` | `Tier F patch` |
| 755 | `No \`signature\`-only permission is needed.` | `Tier P needs no signature-only permission. Tier F grants exactly one, INJECT_EVENTS, to the actuator UID via F7; READ_FRAME_BUFFER / CAPTURE_SECURE_VIDEO_OUTPUT stay ungranted (F5 is scoped to the actuator's a11y screenshot).` |
| 870 | `injection refused while the session holds the turn without an actuator-classified act` | `the framework enforces only the UID grant and provenance stamping (VOS virtual input device + policy flag); the actuator enforces owner_turn-only origin, window/keycode denylists, and the trusted path before each injection` |
| 878 | `About 3 weeks to first boot.` | `About 3 weeks to first boot of the zero-patch build; F5 and F7 add [est] 1–2 weeks each incl. F-PATCH tests and S-10/S-35 reruns; later patches estimated when queued.` |
| 931 | after `CREATE_VIRTUAL_DEVICE\` (S-29/S-30)` | add `. On F only: INJECT_EVENTS (F7) and secure-layer a11y screenshots (F5)` |
| 1018 | `through observed motion events **[spike S-09]**.` | `through observed motion events from the built-in touchscreen only; VOS-injected (F7), dispatchGesture, virtual and external input never count **[spike S-09, S-35]**.` |
| 1070 | `separates real touches from \`dispatchGesture\`` / `injected gestures not reported` | `separates real touches from dispatchGesture, F7 injection and VirtualDevice touch` / `0 non-hardware events counted as presence` |
| 1214 | `Tier F go/no-go (including F6 if duplex blocked).` | `Tier F patch-set decision (go decided, Q16): F5 + F7 fixed; F6 if duplex blocked; F1/F2 from M4 data.` |
| 1246 | `about 3 weeks,` | `about 3 weeks to a zero-patch boot plus F5/F7 [est 2–4 weeks],` |
| 1251 | F-PATCH examples | append `; F5: only the actuator's a11y screenshot has secure layers, MediaProjection/screencap/other a11y stay black; F7: INJECT_EVENTS from any other UID → SecurityException, VOS events never counted as presence` |
| 1332 | `If Tier F triggers, grow` | `Before Tier F starts (right after M4), grow` |
| 1359 | `ONLY if Tier F triggers:` | `Tier F (committed, from right after M4):` |
| 1408 | `receive (redacted) screenshots of your apps` | `receive (redacted) screenshots of your apps, including FLAG_SECURE screens (banking, password managers) once F5 lands` |

L617 doesn't contradict the new rules, but it doesn't say how secure frames are handled. The T7 fix covers it.

## 4. Top 5 fixes, in priority order

1. **Count presence only from hardware touches** (L1018, L1070, S-35, M2-PRES-01). Today F7 can quietly defeat "owner not present → defer", which is the trusted path's last safeguard.
2. **Write a release policy for secure pixels** in §5.8 and §3.5.1: `owner_turn` only, task app must match the window, the session can't pull frames, turn taint blocks other egress, and a spoken cue. Add a test, M4-SEC-01: an injected page asks VOS to read the authenticator app. Pass: 0 `secure_pixels` uploads, and remote MCP blocked after the taint.
3. **Rewrite the F7 scope** (L693, L870): `owner_turn` only, window and keycode denylists, stop paths suspend F7, enforcement split between framework and actuator. Add M4-INJ-02 and M4-INJ-03.
4. **Update §5.1 and §5.2:** add A8 (secure content leaking through declared hosts) and A9 (injection spoofing presence), the F-only permissions in the actuator row, and parser isolation in `isolatedProcess` (T5).
5. **Fix the stale lines** in §3 above. Most important: the 5-patch cap in three places (L102, L161, L192), the "only if needed" wording (L17, L687), the 3-week estimate (L878, L1246), the "go/no-go" at L1214, and F-PATCH tests for F5 and F7 (L1251).
