# App-use workflow for VOS 1.0: the per-step loop, compressed screenshots, and the coordinate transform

| Field | Value |
|---|---|
| Date | 2026-10-18 |
| Author | Claude Code (Opus 5.5), planning only |
| Goal | The per-step loop from "task" to "done" for the 1.0 cloud executor (`ui_step`, decision 30) on Phone B (Pixel 7 `panther`, 1080×2400, 420 dpi), with screenshot compression and coordinate mapping fully specified |
| Plan changes | Decision 39, §3.13, tests M3-IMG-01, M3-IMG-02, M3-COORD-01, M3-COORD-02, §9 Q40–Q42 |
| Scope | No evals run, no adb, no model or network calls. The harness at `~/vos-nav-evals` was outside this session's readable paths, so its capture code was not read; what it does today is taken from `docs/nav-evals/RESULTS-LOG.md` (mode A text list, mode C harness-drawn letter marks) and research/agentic-nav-workflow-2026-10-17.md. Where this doc says "the harness today", check the code. |

## 1. Summary

- **Loop.** Plan once (scope, checklist, done condition), then per step: settle → capture tree + pixels in one epoch → done check (no model) → route replay → tree trust → build the observation (element list as text; pixels only for untrusted regions) → one cloud call that returns a one-line reflection plus one action → validate (ids, transform, snap, rejects) → actuator veto → act → verify with the oracle → stuck signals to the Conductor. No separate reflection or summary model call; the actuator's oracle diff is the reflection input.
- **Action space.** Element-id actions first (`tap(id)`, `type(id, text)`, `set_toggle(id, on|off)`, `tap_seq([id])`, `scroll(id?, toward)`). Coordinates (`tap_point`, `tap_box`, 0–1000 normalized on the image sent) only for targets inside `TREE_PARTIAL`/`TREE_BLIND` regions or on an escalation step, and they always pass through the transform record and the snap rules.
- **Images.** JPEG quality 80 (4:2:0, baseline, no metadata) as the wire default because it is the one lossy format every candidate vendor accepts; WebP quality 75 for vendors whose docs accept WebP (~25–30% fewer bytes); palette PNG for wireframes. Full frame long edge **1000 px** (450×1000 on panther, scale 0.4167); crops at the same scale, or up to native 1.0× for small-text zoom crops; never upscaled. Color by default; grayscale only for text-read crops. Crop to the untrusted regions (decision 38), full frame only on named triggers. **Per-step byte budget: typical ≤60 KB of image, hard cap 150 KB** (encoder steps quality down, then long edge). IME, notification, other-window and secure masks are applied in device pixels before crop and scale. Every choice is validated by M3-IMG-01 (offline sweep on `~/vos-task-data`, desktop only).
- **Coordinates.** One transform record (`vos.img_xform/0`) per image; inverse math is `X = crop.x + (u − pad.l)/sx`, `Y = crop.y + (v − pad.t)/sy` in the current display frame (the frame `getBoundsInScreen` and `dispatchGesture` use), with a rotation step to the natural frame only for injection paths that need it. Then snap to the most specific clickable node (max 8 dp), and reject off-image, padding, masked, inset, wrong-window and stale-image taps with typed reasons. Unit tests: M3-COORD-01 (§6).

## 2. What the field does, and what VOS takes from it

| Work | What it does | What VOS takes | Source |
|---|---|---|---|
| AndroidWorld M3A / T3A | Per step: SoM screenshot plus a text list of UI elements; actions name an element index; a second call writes a step summary (reflection) | Element-index actions, SoM marks on the image matching the text list ids. **Drop** the per-step summary call (decision 29 already does); the actuator's oracle diff replaces it | Rawles et al., *AndroidWorld*, arXiv 2405.14573; github.com/google-research/android_world |
| AndroidControl | Shows high-level goals plus low-level step instructions help; in-domain gains don't transfer for free | Checklist subgoals written at plan time; judge memory on held-out tasks | Li et al., arXiv 2406.03679 |
| AppAgent / AppAgentX | Explore an app, write per-element docs, use them at run time; AppAgentX evolves shortcuts from history | AppMemory screen notes and tips written from task records, routes only from oracle-verified runs (decision 24) | Zhang et al., arXiv 2312.13771; AppAgentX arXiv 2503.02268 [verify id] |
| Mobile-Agent-v2 | Planning, decision and reflection agents; a memory unit for focus content | Plan once (Planner), decide per step (`ui_step`), reflect inline in the same call; scratchpad for focus content | Wang et al., arXiv 2406.01014 |
| Mobile-Agent-E / v3 (GUI-Owl) | Self-evolving tips and shortcuts; v3 adds multi-agent roles over a native GUI model | Tips with helped/hurt scores (already in decision 24); `tap_seq` / `act_seq` as shortcuts | Mobile-Agent-E arXiv 2501.11733; Mobile-Agent-v3 arXiv 2508.15144 [verify id] |
| UFO / UFO2 | Windows agent; UFO2 fuses the UI Automation control tree with vision detection (OmniParser) for controls the tree misses | Exactly decision 38: tree where it agrees with pixels, pixels only where it does not | UFO arXiv 2402.07939; UFO2 arXiv 2504.14603 |
| OmniParser, Set-of-Mark | Detect and number elements on the image so the model answers with a mark id | Marks drawn on the sent image, ids shared with the element list | OmniParser arXiv 2408.00203; SoM arXiv 2310.11441 |
| OS-Atlas, UI-TARS, SeeClick, Qwen2.5-VL | Grounding models output points or boxes; UI-TARS and Qwen use coordinates relative to the resized image (Qwen absolute px on a 28-px-multiple resize; UI-TARS relative ×1000 in v1) | Coordinates as the fallback channel, with the convention (`norm1000` or `image_px`) recorded per vendor and inverted exactly | OS-Atlas arXiv 2410.23218; UI-TARS arXiv 2501.12326; SeeClick arXiv 2401.10935; Qwen2.5-VL arXiv 2502.13923 |
| Ferret-UI / Ferret-UI 2 | "Any resolution": splits a phone screen into sub-images by aspect ratio so small text and icons stay legible | Zoom crops at up to native scale for small text instead of sending the full frame at higher resolution | Ferret-UI arXiv 2404.05719; Ferret-UI 2 arXiv 2410.18967 |
| ScreenSpot-Pro | Small targets on high-resolution screens defeat full-frame grounding; iterative cropping helps | Crop and zoom on untrusted regions; `screen.zoom` | arXiv 2504.07981 |
| Anthropic computer use | Downscale screenshots (XGA-class) and scale model coordinates back; the reference demo has a `scale_coordinates` helper | One transform record; the scaling is explicit and tested | docs.anthropic.com computer-use tool; github.com/anthropics/anthropic-quickstarts (computer-use-demo) |
| Gemini spatial | Boxes as `[ymin, xmin, ymax, xmax]` normalized 0–1000 | `axis_order: yx` in the record for that profile | ai.google.dev, "image understanding" / bounding boxes |

## 3. The loop

### 3.1 Once per task (intake)

1. **Owner turn** (voice or typed, actuator-minted `owner_turn`). The work log starts.
2. **Plan call, text only, before any untrusted observation** (decision 32): the Planner writes `task_scope {apps, classes, flows}`, a checklist of ≤7 subgoals, and `success_when` (decision 25). Inputs: owner words, OwnerMemory lines, the app card (AppMemory), the foreground package. A plan with no done condition is an escalation trigger (decision 26).
3. **Budgets set:** image budget 25 per task (decision 33), step cap 30 [default, proposed], escalations ≤2.
4. **Route lookup** (rung 6): a verified route whose first `screen_sig` matches runs with no model call.
5. Plans longer than ~5 steps or multi-app: the Conductor says the short plan and starts (decision 34).

### 3.2 Per step

Every stage checks the takeover token first; an owner touch stops injection in ≤300 ms and a real takeover cancels the task (decision 34). Paused mode ends the loop.

| # | Stage | Who | Detail |
|---|---|---|---|
| 1 | **Settle** | actuator | Wait for `screen.settled` (decision 28). `loading` >10 s tells the Conductor; >30 s honest stop. |
| 2 | **Capture, one epoch** | actuator | Tree snapshot with a generation counter `tree_gen`, the window list (`AccessibilityWindowInfo` with owner package), WindowInsets, display rotation, and one screenshot taken after the same settle event. `screen_sig` computed. If a tree event arrives between tree and pixels, recapture once. |
| 3 | **Done check** | actuator | Evaluate `success_when` on the tree and oracles **before** any model call. Holds → `VERIFIED` or `SATISFIED`, stop. |
| 4 | **Route replay** | actuator | If a route step matches `screen_sig`, execute it, go to 10. |
| 5 | **Memory** | actuator | `memory.lookup(package, screen_sig)` ≤150 tokens (decision 24), including the cached tree-trust verdict. |
| 6 | **Tree trust** | actuator | Use the cached verdict, or run on-device OCR vs tree (decision 38) and cache it. Regions → `TREE_OK`, `TREE_PARTIAL`, `TREE_BLIND`. |
| 7 | **Build observation** | actuator → session | Element list (text) for tree nodes in the task window, each with `id`, role, label, state flags (`checked`, `enabled`, `selected`, `focused`, editable), `window_pkg`, and a coarse position (row/column bucket, not raw pixels). Pixels by the §4 policy: none, crop, or full frame; masked, cropped, scaled, encoded; SoM marks on any element inside the image; a transform record per image. Audit line `(hash, bytes, text_bytes, destination, turnId)`. |
| 8 | **Step call** | session (`ui_step` via ModelRouter) | Stable prefix (system, tool schema, app card) so vendor caching applies; then goal, checklist with status, last 3 work-log lines with their oracle results, current stuck signals, element list, images with `image_id`. Output JSON: `{reflect: "<≤20 words: did the last action do what was meant?>", checklist: [...status], action: {...}}`. Reasoning effort low; output ≤150 tokens. |
| 9 | **Validate** | session, then actuator | Schema check; ids must be in the current element list; coordinate actions go through the transform record, snap and rejects (§5). Invalid output gets one re-ask with the error; a second invalid output counts toward the escalation trigger (decision 26). |
| 10 | **Veto, then act** | actuator | Stale check (§5.6), risk class and trusted path (§5.5), task scope and window owner (decision 32), taint, blocked areas. A veto returns `veto {reason, class, actId}` and the Conductor replans (decision 22). Execute: `set_toggle` and `tap_seq` re-resolve and check each element before each tap (decision 27); `act_seq` ≤5 actions. |
| 11 | **Verify** | actuator | Wait for settled; screen-diff oracle; per-action expected effect: toggle read back, field text contains what was typed, scroll displacement sign (§3.5.1), `screen_sig` changed after a navigation tap. One work-log line; one task-record step. An unexpected result lowers tree trust for that region (§3.5.1). |
| 12 | **Stuck and recovery** | actuator reports, Conductor decides | Stuck signals (decision 26). Recovery ladder (decision 35): re-observe, one idempotent retry, replan, escalate (1–2 steps on the larger model **with a full frame**), `NEEDS_USER`, honest stop with the partial-completion report. |
| 13 | **Next step or end** | | Step cap or image budget reached → honest stop. On pass: answer tasks get a text-only read over the element list (decision 25); AppMemory write-back runs off the critical path (transitions, screen notes, tree verdict; routes only from `VERIFIED` runs). |

### 3.3 Action space

| Action | Args | Notes |
|---|---|---|
| `tap`, `long_press` | `id` | Preferred. Actuator taps the node's visible-bounds center (§5.5 rule 5) or `ACTION_CLICK` where the node supports it. |
| `type` | `id, text, submit?` | `SET_TEXT` on editable nodes; taint check (decision 32). |
| `set_toggle` | `id, on\|off` | Decision 27. |
| `tap_seq` | `[id]` | Decision 27; stops at first miss. |
| `scroll` | `id?, toward=end\|start\|next\|prev` | Content-intent names only (§3.5.1). |
| `pan` | `reveal=n\|s\|e\|w` | Maps. |
| `back`, `home`, `open_app` | `pkg` for `open_app` | `open_app` checked against scope. |
| `zoom` | `region_id` or `image_id + box` | Requests a native-scale crop on the next step (`screen.zoom`). |
| `tap_point` | `image_id, x, y` | Fallback. Only inside `TREE_PARTIAL`/`TREE_BLIND` regions, or on an escalation step. |
| `tap_box` | `image_id, [x1, y1, x2, y2]` | Fallback; box center plus box-overlap snapping. |
| `answer` | `text` | Answer tasks; checked against the element list where possible. |
| `goal_reached` | `why` | A hint only: it triggers the done check, never ends the task by itself (P3). |
| `needs_user`, `give_up` | `reason` | Honest stop paths. |

### 3.4 Per-step budget (cloud step, Wi-Fi)

| Item | Typical | Max | Note |
|---|---|---|---|
| Stable prefix (system, tools, app card) | ~1,500 tok, cached | 2,500 tok | Vendor prompt caching where offered (§3.5.1 rule 2) |
| Goal, checklist, history (3 lines), stuck signals | ~300 tok | 600 tok | |
| Element list | ~600 tok (≈40 nodes × 15) | 1,500 tok (80 nodes; the rest summarized as "+N rows below") | `text_bytes` audited (decision 33) |
| Image tokens | 0 (all `TREE_OK`) / ~150–400 (crop) / ~450–800 (full 450×1000) | ~1,600 (full frame + one zoom crop) | Vendor-dependent; see §4.2; record the vendor's reported count |
| Image bytes (before base64) | ≤60 KB | 150 KB hard cap | Base64 adds 33% on the wire |
| Request body | ≤100 KB | 250 KB | |
| Output | ≤100 tok | 150 tok | Low reasoning effort |
| Device time: capture + tree + encode | ≤150 ms | 300 ms | Capture ≤80 ms (§3.5.1); JPEG encode of 450×1000 is tens of ms [est] |
| Tree trust OCR (new screens only) | ≤250 ms [est] | 600 ms [est] | Cached per screen |
| Upload | ~50 ms Wi-Fi | ~0.5 s LTE | 60–150 KB |
| Model | p50 2.4 s (Grok, measured) | stall cap 30 s | |
| Settled → action parsed | target p50 ≤2.5 s | p95 ≤6 s | Existing targets (§3.5.1), not gates |
| Act → settled | ~0.4–0.7 s | | 300 ms quiescence + animation |

The harness today runs about 8 s per Grok step. I could not read the harness in this session, so the split between capture (adb `screencap` of a full-resolution PNG is the usual slow part), fixed sleeps, upload, and model time is unmeasured. M3-IMG-02 logs that split first, so the budget above is checked against data rather than guessed.

## 4. Screenshot compression (Caleb's requirement 1)

### 4.1 Settings

| Choice | Setting | Why | Validated by |
|---|---|---|---|
| **Format** | **JPEG q80**, 4:2:0, baseline, EXIF/ICC stripped, sRGB. **WebP q75** (lossy, method 4) where the vendor's docs list WebP. **PNG-8** (palette) for wireframes and wireframe regions. Never PNG-24 for photos of the screen. | xAI's image docs list JPEG and PNG [verify WebP], and xAI is the default vendor (Q5), so JPEG is the safe default. WebP is ~25–34% smaller than JPEG at equal SSIM (Google WebP study) and Anthropic, OpenAI and Gemini accept it. Wireframes are flat colors: palette PNG is smaller than JPEG and has no ringing on the labels. | M3-IMG-01 format × quality grid: bytes, OCR recall, toggle-state accuracy, then grounding and action agreement |
| **Quality** | JPEG 80, WebP 75 | Below ~70, JPEG ringing around 11–12 px text starts to break OCR of small labels [est]; above ~85 bytes grow fast for no visible gain. The knee is expected near 75–80 and is set by the sweep. | M3-IMG-01: OCR recall on small-text nodes vs full-resolution PNG |
| **Long edge, full frame** | **1000 px** → 450×1000 on panther (scale 0.4167) | Vendor cost is set by pixel dimensions, not bytes. At 1000 px: Anthropic ≈ w·h/750 ≈ 600 tokens and under its 1568 px resize limit; OpenAI high detail = 2 tiles of 512 ≈ 425 tokens (85 + 2×170); Gemini ≈ 2 tiles of 768 ≈ 516 tokens [verify current counting]. 1280 px costs ~1.6× the tokens; 720 px drops 11 sp text to ~9 px tall. 14 sp body text (37 px at 2.625 density) lands at ~15 px, which VLMs read; 11 sp lands at ~12 px. Matches the ~1000 px already in §3.5.1 for `ui_verify`. | M3-IMG-01 long-edge sweep {720, 896, 1000, 1280, 1568, 2400}; knee on grounding hit rate vs tokens |
| **Tile snap** | Per vendor profile, round the image down to the vendor tile grid when that costs ≤5% scale (e.g. width 448 instead of 450 for a 448-px tiler) | Avoids paying a whole extra tile column for 2 px | Vendor-reported token counts in M3-IMG-01 |
| **Crops** | Same scale as the full frame (0.4167), so text size is consistent across steps. **Zoom crops** (small text, `zoom` action, `screen.zoom`) at up to native 1.0×, capped at 1000 px long edge. Never upscaled above native. | Ferret-UI and ScreenSpot-Pro: small targets need more pixels, not a bigger full frame | M3-IMG-01 small-text subset: zoom crop vs full frame at 1280 |
| **Color** | **Color by default.** Grayscale only for `read` crops whose content is text (a `TREE_BLIND` text block, a dialog body) | Grayscale changes no vendor's token count and saves only ~10–15% JPEG bytes (chroma is already subsampled), while switch state, selected tabs, error states and maps are often color-only (Material You switch on = tinted track) | M3-IMG-01: toggle/selected-state classification from pixels, color vs gray; bytes saved |
| **Crop vs full (decision 38)** | **None** when every region in the task window is `TREE_OK`. **Crop** to the union of `TREE_PARTIAL`/`TREE_BLIND` regions plus 24 dp padding, merged when two crops are within 48 dp; at most 2 crops per step. **Full frame** when: the crop union would exceed 60% of the screen; an escalation step (decision 26); a stuck signal or an unexpected result on the last step; `ui_verify` before an irreversible act (needs full mark coverage, M3-VERIFY-02); a screen-level question ("what's on this screen"); map look-around; first step after a tree-trust re-check flips the screen. | Fewer pixels to the cloud (decision 33), fewer tokens, and the trusted text is more exact than pixels | M3-IMG-01 crop-policy arm (offline grounding), then lane L1/L2 pass rate on the phone (decision 33's adoption rule) |
| **Masks** | IME region, notification shade and heads-up, other packages' windows, unattributed windows, finance/comms content rows outside scope (decision 33, §5.8): solid fill in **device pixels before crop and scale**, labeled placeholder. Secure regions: wireframe until F5, then the secure-pixel policy. | A solid fill survives any compression with no leak; masking before scaling means mask rects and tap rejects share the device frame | M3-PRIV-01 (existing); M3-IMG-01 checks masks survive every format/quality (no ringing outside the rect, no content inside) |
| **Marks** | Drawn after scaling, at image resolution: numbered boxes on every element inside the image, ids equal to the element-list ids; 2 px outline, label ≥12 px tall, placed to avoid covering the element's own text | Marks drawn before scaling blur at 0.42×; SoM needs crisp labels | M3-IMG-01 arm: marks vs no marks on grounding |
| **Byte budget** | Typical ≤60 KB, hard cap 150 KB per step (all images). Over the cap: re-encode at q70, then q60, then drop the long edge to 896, then send the crop only. Each fallback is logged. | Bytes cost upload time on LTE and audit size, not vendor tokens | M3-IMG-02 logs typical/p95 bytes per app class |

**Expected sizes at JPEG q80, 450×1000 [est, to be measured]:** Settings-style lists 25–45 KB; Spotify with album art 60–100 KB; Maps 80–130 KB; dark theme slightly smaller than light. WebP q75 ≈ 0.7× those. Crops scale with area.

### 4.2 Vendor image costs (look these up again before M3-IMG-01; [verify] all)

| Vendor | Rule as documented (as I recall it) | 450×1000 cost |
|---|---|---|
| Anthropic | tokens ≈ w·h/750; long edge >1568 px is downscaled | ≈600 |
| OpenAI (GPT-4o-class, high detail) | fit 2048², shortest side to 768 if larger, 512-px tiles × 170 + 85 | 2 tiles ≈ 425 |
| Google Gemini | ≤384 px both sides = 258; larger tiled at 768² × 258; newer models have a `media_resolution` setting | ≈516 |
| xAI Grok | tiles × fixed tokens per tile [verify tile size and count]; JPEG and PNG accepted [verify WebP] | measure in M3-IMG-01 |

### 4.3 Validation: M3-IMG-01 (offline sweep, desktop only)

- **Data.** Stored screenshots, a11y dumps and task records in `~/vos-task-data` on caleb-Z790 (never GitHub). Stratify about 200 frames [est] by app class: Settings/list, Clock/Calculator, Files, Vanadium web page, Spotify (art-heavy, dense), Maps, light and dark theme, one landscape set. Frames already carry the redaction.
- **Grid.** format {JPEG q60/70/80/90, WebP q60/70/75/80/90, PNG reference, PNG-8 for wireframes} × long edge {720, 896, 1000, 1280, 1568, 2400 native} × {color, gray} × {full, crop policy, marks on/off}.
- **No-model metrics (can run any time on the desktop):** bytes; encode time; OCR text recall against the tree text of `TREE_OK` regions and against OCR of the native PNG, overall and for small text (<12 sp); toggle and selected-state classification from pixels vs the tree's `checked`/`selected`; mask integrity.
- **Model metrics (a future run; calls a vendor, so it needs Caleb's OK on spend and on which vendor sees the frames, §9 Q41):** element-grounding hit rate (ask for a named element; hit = the inverse-transformed point lands in that node's bounds, using the M3-COORD-01 code); action agreement on replayed steps (same prompt, image varied; agreement with the native-PNG action and with the action from passing runs); vendor-reported image tokens.
- **Pick.** For each vendor profile, the cheapest setting (tokens first, bytes second) with OCR recall ≥98% of native, grounding hit rate within 1 point of native, and action agreement ≥95% of native [targets]. Plot the curves and take the knee; record the chosen values and the curves in the plan change that adopts them. Grayscale is adopted for `read` crops only if state accuracy is unaffected there.

## 5. Coordinate mapping (Caleb's requirement 2)

### 5.1 Frames

- **Natural frame:** the panel in its natural orientation, `nat_w × nat_h` = 1080 × 2400.
- **Display frame:** the current orientation. At rotation 0/180 it is 1080 × 2400; at 90/270 it is 2400 × 1080. `AccessibilityNodeInfo.getBoundsInScreen`, window bounds, WindowInsets and `dispatchGesture` all use this frame, so **every rect in the transform record is in display-frame pixels**.
- **Image frame:** pixels of the image as sent, `img_w × img_h`, origin top-left, continuous coordinates (pixel *i* spans [i, i+1)).
- The device pipeline always rotates the captured buffer into the display frame before masking and cropping, so the model always sees an upright image. Which buffer orientation `AccessibilityService.takeScreenshot`, `screencap` and the F5 frame-buffer path return on panther is checked by M3-COORD-02, not assumed.

### 5.2 Transform record (`vos.img_xform/0`), attached to every image sent

| Field | Type | Example (full frame, portrait) | Meaning |
|---|---|---|---|
| `image_id` | string | `t42-s07-i1` | Unique per image; the model must echo it in coordinate actions |
| `image_sha256` | hex | | Audit link |
| `task_id`, `step` | | | |
| `captured_uptime_ms` | int | | For the age check |
| `tree_gen` | int | 41 | Tree generation at capture |
| `screen_sig` | string | | §3.9 |
| `nat_w`, `nat_h` | int | 1080, 2400 | Natural panel size |
| `rotation` | 0/90/180/270 | 0 | Display rotation vs natural, defined by the §5.3 formulas; the Android `Surface.ROTATION_*` label mapping is fixed by M3-COORD-02 |
| `disp_w`, `disp_h` | int | 1080, 2400 | Display frame size (swapped at 90/270) |
| `density_dpi` | int | 420 | dp → px = dpi/160 = 2.625 |
| `crop` | `{x, y, w, h}` display px | `{0, 0, 1080, 2400}` | Region of the display frame in the image |
| `sx`, `sy` | float | 0.416667, 0.416667 | Image px per display px, computed as `content_w / crop.w`, `content_h / crop.h` after integer rounding (so they can differ slightly) |
| `pad` | `{l, t, r, b}` image px | `{0, 0, 0, 0}` | Letterbox padding (only for vendor profiles that need a fixed size) |
| `img_w`, `img_h` | int | 450, 1000 | |
| `insets` | `{top, bottom, left, right}` display px | e.g. `{118, 63, 0, 0}` [measured at capture, example values] | Status bar, nav bar, cutout from WindowInsets |
| `masks` | `[{x, y, w, h, kind}]` display px | IME, `other_window`, `notif`, `secure` | Areas that are masked in the image; taps there are rejected |
| `windows` | `[{window_id, pkg, type, bounds}]` | | Window-owner map at capture (decision 32) |
| `coord_mode` | `norm1000` \| `image_px` | `norm1000` | What the vendor profile asks the model to return |
| `axis_order` | `xy` \| `yx` | `xy` | `yx` for Gemini-style `[ymin, xmin, ymax, xmax]` |

### 5.3 Math

Forward (display → image), used to draw marks and to project node bounds into the image:

```
u = (X − crop.x) · sx + pad.l
v = (Y − crop.y) · sy + pad.t
```

Inverse (model output → display), the path every coordinate action takes:

```
if axis_order == yx: swap the pair (and box corners) to (x, y)
if coord_mode == norm1000:  u = nx / 1000 · img_w ;  v = ny / 1000 · img_h     (nx, ny ∈ [0, 1000])
else:                       u = px ;  v = py
reject off_image   if u < 0 or u > img_w or v < 0 or v > img_h
reject out_of_crop if u < pad.l or u > img_w − pad.r or v < pad.t or v > img_h − pad.b
X = crop.x + (u − pad.l) / sx
Y = crop.y + (v − pad.t) / sy
X, Y = clamp to [crop.x, crop.x + crop.w − 1] × [crop.y, crop.y + crop.h − 1], then round to integer px
```

Boxes: invert both corners, sort them, and use the box center as the point; the box is also used for snapping (§5.5).

Display ↔ natural, only for injection or capture paths that work in the natural frame (W = `nat_w`, H = `nat_h`):

| rotation | natural → display | display → natural |
|---|---|---|
| 0 | `(x, y)` | `(x, y)` |
| 90 | `(y, W − x)` | `(W − Y, X)` |
| 180 | `(W − x, H − y)` | `(W − X, H − Y)` |
| 270 | `(H − y, x)` | `(Y, H − X)` |

Accessibility gestures (`dispatchGesture`) take display-frame coordinates, so the natural step is skipped for them. Whether F7 `INJECT_EVENTS` and `input tap` use display or natural coordinates is part of M3-COORD-02 [spike].

### 5.4 Rejects (typed reasons, logged in the task record)

| Reason | When |
|---|---|
| `off_image` | Point outside [0, img_w] × [0, img_h], or a normalized value outside [0, 1000] |
| `out_of_crop` | Point inside the letterbox padding |
| `masked_region` | Display point inside a `masks` rect (IME, notification, another window, secure without F5) |
| `system_inset` | Display point inside the status bar or nav bar inset (shade and system navigation use actuator tools, never model taps) |
| `scope_violation` | The window under the point, by the capture map **and** a live re-check, belongs to a package outside the task scope, or cannot be attributed (decision 32 window-owner rule) |
| `stale_image` | `image_id` is not from the latest observation, or `tree_gen`/`screen_sig` changed since capture, or the image is older than 30 s (the stall cap) |
| `no_element` | No snap target within the snap distance and the point is in a `TREE_OK` region (the tree is trusted there, so the model missed) |
| `ambiguous_snap` | Two clickable candidates within 4 px of the same snap distance |
| `unknown_image` | `image_id` not issued in this task |

A reject is returned to the step model as a short error (it counts toward the invalid-output trigger) and never retried at the same point.

### 5.5 Snapping to accessibility elements

Candidates: visible, enabled nodes in the window under the point, in that window's z-order, from the tree with the same `tree_gen` as the image.

1. **Inside a clickable node** (clickable, long-clickable, checkable, or editable): take the smallest-area such node containing the point.
2. **Inside a non-clickable node:** walk up to the nearest clickable ancestor that contains the point (a label inside a row → the row).
3. **Not inside any node:** take the clickable node whose rect is nearest (distance from point to rect edge) within **8 dp** (21 px on panther), measured in display px. In box mode, prefer the clickable node with the highest box overlap (IoU ≥0.5) before the distance rule.
4. **Nothing within 8 dp:** in a `TREE_BLIND` or `TREE_PARTIAL` region, tap the raw point (that is what the fallback is for); in a `TREE_OK` region, reject `no_element`.
5. **Tap point after a snap:** if the point is inside the snapped node, keep it, moved 2 px inside the node's edge; if it was snapped from outside, use the center of the node's visible bounds (bounds ∩ window ∩ screen minus insets). Then hit-test: the deepest clickable node at the final point must be the snapped node; if not, use the visible center; if that fails too, reject `ambiguous_snap`.
6. **Before the tap** the actuator re-reads that node (stale check, §3.5.1 "re-read the tree immediately before the tap").

### 5.6 Unit tests (M3-COORD-01, pure functions, no device)

Panther natural 1080×2400, density 420 (8 dp = 21 px). Insets example `{top: 118, bottom: 63}`. Coordinates are expected display px after rounding.

| # | Setup | Input | Expected |
|---|---|---|---|
| 1 | Full frame, rot 0, crop (0,0,1080,2400), image 450×1000, sx=sy=0.416667, no pad, `image_px` | (225, 500) | (540, 1200) |
| 2 | same, `norm1000` | (500, 500) | (540, 1200) |
| 3 | same, `norm1000` | (100, 50) | u=45, v=50 → (108, 120) |
| 4 | same, `norm1000` | (1000, 1000) | clamp → (1079, 2399) → reject `system_inset` |
| 5 | same, `norm1000` | (1001, 500) | reject `off_image` |
| 6 | same, `image_px` | (−3, 10) | reject `off_image` |
| 7 | same, `norm1000` | (500, 20) | (540, 48) → reject `system_inset` (Y < 118) |
| 8 | Crop offset: crop (0,1440,1080,960), image 450×400 | `image_px` (225, 200) | (540, 1920) |
| 9 | same | `norm1000` (250, 750) | u=112.5, v=300 → (270, 2160) |
| 10 | Zoom crop at native: crop (100,300,200,100), sx=sy=1.0, image 200×100 | `image_px` (150, 50) | (250, 350) |
| 11 | Letterbox: crop (0,600,1080,1200) into 1000×1000, scale 0.833333, content 900×1000, pad l=50, r=50 | `image_px` (500, 500) | (540, 1200) |
| 12 | same | `image_px` (20, 500) | reject `out_of_crop` |
| 13 | same | `image_px` (50, 0) | (0, 600) |
| 14 | same | `norm1000` (950, 1000) | u=950, v=1000 → X=1080 → clamp (1079, 1799) |
| 15 | Rotation 90: display 2400×1080, full frame, image 1000×450, s=0.416667, insets 0 | `image_px` (100, 50) | display (240, 120); natural (960, 240) |
| 16 | Rotation 270: same image | `image_px` (100, 50) | display (240, 120); natural (120, 2160) |
| 17 | Rotation 90 round trip | natural (960, 240) → display → natural | (240, 120) → (960, 240) |
| 18 | Box, full frame portrait, `norm1000`, `xy` | [100, 200, 300, 260] | image (45,200)–(135,260) → display (108,480)–(324,624) → center (216, 552) |
| 19 | Same box, `yx` (Gemini order) | [200, 100, 260, 300] | (216, 552) |
| 20 | Box with corners reversed | [300, 260, 100, 200] | sorted, (216, 552) |
| 21 | Snap: row N1 clickable [0,1000,1080,1160] (l,t,r,b); switch N2 clickable [900,1040,1040,1120] inside N1; label N3 non-clickable [42,1040,600,1120] | point (300, 1080) | N3 → ancestor N1; tap (300, 1080) |
| 22 | same tree | point (950, 1080) | N2 (smallest clickable); tap (950, 1080) |
| 23 | same tree | point (540, 1175) (15 px below N1) | snap N1; tap N1 visible center (540, 1080) |
| 24 | same tree, region `TREE_OK` | point (540, 1200) (40 px away) | reject `no_element` |
| 25 | same tree, region `TREE_BLIND` | point (540, 1200) | raw tap (540, 1200) |
| 26 | Buttons N4 [100,1300,300,1400], N5 [330,1300,530,1400] | point (315, 1350) | reject `ambiguous_snap` |
| 27 | Mask: IME rect (0,1500,1080,837) | point (540, 1800) | reject `masked_region` |
| 28 | Window map: overlay window `com.example.overlay` [0,800,1080,1400], not in scope | point (540, 1000) | reject `scope_violation` |
| 29 | Window under the point has no owner | point inside it | reject `scope_violation` |
| 30 | Record `tree_gen` 41, current 43 and `screen_sig` changed | any valid point | reject `stale_image` |
| 31 | Record captured 31 s ago | any valid point | reject `stale_image` |
| 32 | `image_id` from an earlier step | any | reject `stale_image`; unknown id → `unknown_image` |
| 33 | Non-uniform scale after rounding: crop (0,1400,1080,800), image 450×333, sx=0.416667, sy=0.41625 | `image_px` (225, 333) | Y = 2200 → clamp (540, 2199) |
| 34 | Forward/inverse property | 1,000 random display points in crop, all setups above | inverse(forward(p)) within 1 px |

M3-COORD-02 (on Phone B at M3, not now): on five screens in portrait and landscape, project every clickable node's center into the image and back and check it lands in the node; take one screenshot through each capture path and check its orientation; confirm which `Surface.ROTATION_*` value matches the 90 and 270 rows above; confirm which frame F7 injection uses.

## 6. Plan changes

- Decision 39 (compact, after 38).
- §3.13 "App-use step loop, images and coordinates" with the loop, the image settings, the transform record and the snap and reject rules; the full math and test cases stay here.
- Tests: M3-IMG-01, M3-IMG-02, M3-COORD-01, M3-COORD-02.
- §9: Q40 wire format per vendor; Q41 which vendors may receive stored frames for the model half of M3-IMG-01; Q42 step cap per task.
- Change-log row and Date line.

## 7. Open points for later

- The exact token rules for xAI images (tile size, whether WebP is accepted) are the biggest unknown for the default vendor; check xAI's current docs before the sweep.
- Element-list pruning (which nodes, how many, the coarse position buckets) is a harness experiment in the P0–P10 order (§3.8.1), not a plan item.
- A text-only step for all-`TREE_OK` screens is the cheapest step and decision 38 asks for it; decision 33 says text-only steps are adopted only where lane L1/L2 pass rates hold. M3-IMG-01's crop-policy arm plus the lanes settle that.
