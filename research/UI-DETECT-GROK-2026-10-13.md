# On-device UI control detection for VOS — independent second opinion

| Field | Value |
|---|---|
| Date | 2026-10-13 |
| Author | Grok (independent of `UI-DETECT-RESEARCH-2026-10-12.md`; that file was not read until section 6) |
| Question | What should fill `ui.control_detect` on Pixel 7 (`panther`, Tensor G2) and Pixel 8a (`akita`, Tensor G3), GrapheneOS, actuator with no network, contract `{bbox or center, type, label?, confidence}`, budget ≤120 ms p50 / ≤250 ms p95 on panther |
| Status | Research opinion. No on-device measurement was run for this note. Figures taken from vendor docs or papers are cited; anything not measured here is marked **[verify]** or **[est]**. |

This note argues a position: **do not make a fine-tuned YOLO the product.** Use the OS tree as the primary proposal source, a small license-clean pixel detector only where the tree is blind or lying about bounds, OCR for labels and for `ocr_commit`, and cloud vision (Set-of-Mark) for semantic choice. A framework snapshot of View + Compose semantics + WebView boxes is the highest-upside OS change, and it is optional for v0.

---

## Constraints this opinion is bound by

From `docs/TARGET-PLAN.md` (2026-10-12), not re-litigated here:

- Actuator (`com.vos.actuator`) has no `INTERNET`. `control_detect` and `ocr_commit` cannot be cloud models. Cloud is `vision_step` in the session, on a redacted screenshot, one escalation.
- Output is proposals, not a decision. The Operator taps only when confidence ≥ τ and the type matches the goal. Lists of same-type rows (Spotify) must escalate. Commit detection stays a separate, always-on-device check.
- Screenshot + a11y snapshot budget is ≤80 ms. Detect is a *separate* ≤120 ms p50. Gesture settle ≤300 ms. M3-UI-05 wants ≥60% of successful cold steps to stay on-device.
- Eval is live current apps (Maps / Organic Maps, Spotify, Signal, settings, Vanadium). Version-pinning the app under test is forbidden. Routes are cached after a verified run and invalidated on signature drift.
- Tiers: **S** is a normal app (accessibility only). **P** is Graphene's official OTA plus a product overlay, Graphene source unmodified. **F** is a short named patch queue, used only when a Must row needs it. Root and `INJECT_EVENTS` are rejected. `FLAG_SECURE` is never bypassed and never uploaded.
- Intents are a weak probe. The durable UI path is screenshot → proposals → tap.

Phone facts that change the model choice:

- Pixel 7 is 1080×2400 **[verify]**. Small controls (map chrome, icon buttons) are on the order of a few dozen pixels. A 640-wide detector input throws away a lot of them.
- Google's public **Tensor SDK / LiteRT NPU path supports Tensor G5 and G6 only** (Pixel 10 and 11 families). Docs updated 2026-09-02 and 2026-09-08 list those SoCs and no earlier Tensor. The May 2026 Tensor SDK beta post names Pixel 10 devices. **panther and akita do not get that TPU delegate.** NNAPI, the old route to the Pixel TPU, was already deprecated by Android 15. Realistic backends on these two phones are **CPU (XNNPACK) and GPU (Mali via LiteRT / ML Drift OpenCL)**.
- Published phone GPU number, for scale only: Ultralytics' LiteRT table for **YOLO26n detect, 640, w8a32, Pixel 10 (Tensor G5)** reports mean **45.5 ms on GPU** (3.8 / 37.7 / 4.0 pre / infer / post) and **53.3 ms on CPU**. That chip is two GPU generations newer than the Mali-G710 in the Pixel 7 and newer than the Mali-G715 in the 8a. It is **not** a panther measurement. A nano CNN *can* fit in 120 ms on panther and it can also miss; that is a spike, not a literature fact.
- Qualcomm NPU numbers (YOLO-v7 Tiny ~13–18 ms TFLite on Snapdragon 8 Gen 2/3; MobileSAM encoder ~73 ms QNN / ~126 ms TFLite on an S24) describe a different vendor stack. They are evidence that "mobile NPU YOLO is fast," and evidence that **we do not have that NPU.**

---

## 1–2. The solution space

Each family is judged against the contract (boxes + type + optional label + confidence, 120 ms p50 on G2/G3, no network, shippable license). "Latency" is end-to-end for that stage on panther unless a different device is named.

### A. Accessibility / view-hierarchy only

**What.** `AccessibilityService` already receives `AccessibilityNodeInfo` trees and `AccessibilityWindowInfo` (`getWindows()`, API 21+). Each node can carry bounds in screen space, text, content description, class, clickable / long-clickable / editable / checkable / enabled, and actions. That maps onto the contract with no model: clickable → `button` or `link`, editable → `field`, checkable → `toggle`, image-only clickable → `icon`, else `other`. Confidence is a heuristic (unique label match to the goal is high; unlabeled clickable is medium; a node whose bounds are the whole screen is low).

`takeScreenshot` (API 30) and `takeScreenshotOfWindow` (API 34) are the pixel path beside it. The platform returns `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` when called too fast. The plan already spikes the real rate (S-10). Do not assume the 80 ms capture budget until that spike passes.

**Pros.** Tier S. Zero model, zero license, type and label come from the app, bounds are the OS's hit target rather than a regression. This is the only family that is *known* to fit in 120 ms, because it is not inference: a walk of a few hundred nodes is **[est]** well under 30 ms once the service has the event. Deterministic enough to log and to replay as a route signature. Works on Settings, SystemUI, most of Signal, stock Android widgets, and a large share of Compose apps that set semantics.

**Cons.** The tree is a claim by the app, and apps lie or omit:

- Custom `onDraw` controls that never fill `AccessibilityNodeInfo`.
- Compose composables drawn but not exposed (`Modifier.semantics` missing, or merged into a parent). Trailblaze's Oct 2026 notes describe this as a normal cause of "element not found" on a screen that looks complete.
- `importantForAccessibility = no` / `hideFromAccessibility`. Decorative on purpose, or a control hidden by mistake. The pixel is still tappable.
- One node for a whole row (icon + title + overflow). Bounds are too coarse to tap the right child. This is the Spotify failure mode even when every row *is* in the tree.
- WebView: the accessibility bridge is asynchronous and often incomplete; a Flutter-web-in-WebView bug (flutter#175465, 2025) delayed the semantics tree until the first user gesture.
- Flutter's own bridge turns semantics on for assistive tech. Whether a silent VOS accessibility service (not TalkBack, touch exploration off) makes Flutter publish nodes is **[verify]**. If it does not, Flutter apps are pixel-only until we ask the framework to treat our service as a screen reader, which has UX side effects.
- Games, map canvases, video, GL: one `SurfaceView`. Pins, roads, and custom HUD are not nodes.
- Bounds can be stale during animations, or include off-screen children.

**Latency.** Inside the 80 ms snapshot budget if the cache is fresh. Not a 120 ms model.

**License.** Platform API. No model license.

**Maturity.** Production, and the standard tool of every Android UI test runner. Completeness on 2026 apps is the open measurement, not the API.

### B. Privileged view hierarchy and render-tree dumps (OS, because we can)

A normal app cannot see another app's `View` tree. The phone can. Several layers exist, and they are not the same data.

**B1. `dumpsys activity top` / `ViewDebug.dump`.** Shell and the `DUMP` permission (signature|privileged) can print the focused activity's `View` hierarchy: class, resource id, visibility, clickable flag, bounds. `uiautomator dump` is the accessibility tree written to XML, which an accessibility service already has, so it adds nothing once the service is connected.

What a View dump adds over accessibility: views with `importantForAccessibility=no`, and resource ids. What it does **not** add: Compose children (they are semantics inside one `AndroidComposeView`), WebView DOM, Flutter, and anything drawn in a single custom `onDraw` or GL surface. Format is text and has broken across releases. Latency of a full `dumpsys` is **[est]** tens to a couple hundred ms and allocates; a direct binder walk would be cheaper. On Tier P this is available only if the actuator is a privileged product app whitelisted for `DUMP`. avbroot re-signs the image with the owner's key; whether that key is the platform key that `DUMP` checks is **[verify]**. If it is not, this is a Tier F (or a privapp whitelist) change, not a free Tier P trick.

**B2. The patch worth writing, if any OS patch is written.** A system service, callable only by the actuator UID, that on request returns a parcel per window:

- Walk `ViewRootImpl` views: bounds, class, resource-id, text, clickable, enabled, drawing cache bounds.
- If the root is Compose, also walk `SemanticsOwner` (the same structure accessibility uses, including nodes the app hid from accessibility — policy decision, see risks).
- If the root is a WebView, ask the accessibility node provider, and if that is empty run a small JS `getBoundingClientRect` dump over elements with click handlers, roles, or cursor pointer. Third-party WebViews need a WebView-provider hook; Vanadium alone is not enough.
- Do not try to parse GL. Return a flag `pixel_only` for `SurfaceView` / `TextureView` regions so the detector knows where it is responsible.

Returned over binder or a shared-memory buffer. Cost should be a tree walk, **[est]** 1–10 ms for ordinary screens, not a neural net. This is Tier F (framework + WebView), one named patch, and it is justified only after the Tier S tree-blind rate on the M3 app set is known. It does not violate "no root" and it does not need `INJECT_EVENTS`.

**B3. Input path as a labeler, not a detector.** `View.performClick`, `Compose` click modifiers, and `InputDispatcher` already know which target accepted a touch. A ring buffer of `{time, bounds, class, resource-id, source=finger|gesture}` is the best auto-label stream available (section 4). It does not list controls before the tap. It confirms a tap and teaches the next model. It is also a UI keylogger. Actuator-only, owner-visible, never uploaded, off when the goal does not need it.

**B4. Hit-test grid.** Expose `hitTest(x, y)` and sample the screen. This rediscovers the tree at much higher cost and still misses GL. Inferior to B2.

**B5. SurfaceFlinger / HWUI damage.** Layer bounds and damage rectangles say which window and which rectangle changed. They do not say "this rectangle is a button." Use them to crop the next detect and to feed `ui.oracle` (the map moved; a dialog appeared). Reading them is system-only. Latency is effectively free if the compositor already computed the damage **[est]**.

**B6. Press and ripple.** `RippleDrawable` and pressed-state draw a short-lived highlight, often ~100–300 ms **[est, verify]**. A frame grabbed immediately after an injected tap localizes the control that actually took the hit, including custom-drawn controls the tree missed. That is an oracle and a label, not a way to choose the first tap.

**B7. What not to bother parsing.** HWUI display lists are full of shadows, text runs, and nine-patches. Turning them into controls is a worse detector than a small CNN, with a private unstable schema.

**Pros of B2.** Exact bounds for every real View and every Compose semantics node, including ones accessibility hid. Type and id from the toolkit. Makes the pixel model a fallback for GL and custom canvas only. Turns the owner's normal use into labeled data via B3.

**Cons.** Tier F maintenance on every Graphene rebase. Graphene's patch budget in the plan is five named patches; this competes with audio. Compose and WebView internals move. Hidden-from-accessibility nodes may include fields the app deliberately did not expose (password dots are fine; some banking overlays are not). Must not become a `FLAG_SECURE` bypass: if the window is secure, return no pixels and only the node metadata the accessibility policy already allows **[policy choice]**.

**License.** AOSP. No model.

**Maturity.** The data structures are mature. The VOS service does not exist. Treat as a design, not a component.

### C. Pixel detectors (YOLO family, DETR family)

**What.** One-shot bounding-box models. For UI they are usually trained as one class (`control` / `icon`) or a small taxonomy (`button`, `field`, `icon`, `toggle`, `text`). They do not know the goal. They do not read text unless a second head or a later OCR does.

Candidates that are real in October 2026:

| Family | Why it is on the list | License | Phone-shaped? |
|---|---|---|---|
| Ultralytics YOLOv8 / YOLO11 / YOLO26 / YOLOE-26 | Best tooling Caleb already knows. YOLO26n is ~2.4–2.6M params, 40.9 COCO mAP, 1.7 ms on a T4 TensorRT, **38.9 ms CPU ONNX on a Xeon**, NMS-free end-to-end option. YOLOE adds open-vocab text prompts. | **AGPL-3.0**, enterprise license for closed use. Ultralytics has said the AGPL covers weights trained with their code. VOS is a personal open tree today; AGPL still reaches the actuator if that code is linked and the image is distributed. | Export to LiteRT exists (2026). Pixel 10 GPU ~45 ms for YOLO26n at 640 (above). **No G2 number.** |
| YOLOX (Megvii) | Anchor-free, the usual "permissive YOLO." | Apache-2.0 | Upstream frozen at 0.3.0 (2022). Fine for a fork. Export is do-it-yourself. |
| YOLOv9 (WongKinYiu) and the MIT reimplementation | OmniParser's 2026 MIT detector is a YOLOv9-E distill. | Original **GPL-3.0**. A MultimediaTechLab reimplementation is MIT **[verify the repo before linking]**. | YOLOv9-E is the large end of the family. Too big to be the 120 ms model unless heavily cut. |
| PP-YOLOE / DAMO-YOLO | Apache real-time detectors. | Apache-2.0 | Mobile sizes exist in Paddle; Android export is Paddle Lite, a second runtime beside LiteRT. |
| RT-DETR, D-FINE, DEIM, DEIMv2, RF-DETR | Transformer detectors. RF-DETR-N (Apache, ICLR 2026) reports 48.4 COCO AP at **2.3 ms on a T4**, but it is **30.5M params** at 384 — a "nano" that is ~10× YOLO26n. XL/2XL are PML 1.0, not Apache. D-FINE is Apache-2.0. | Apache for the open sizes | **Poor mobile-export evidence.** A community LiteRT port of D-FINE-S reported ~450 ms/frame on a phone GPU and a second graph at 2193 ms, blaming the deformable decoder. D-FINE-S INT8 on an Intel N150 (OpenVINO, CPU) was 76.3 ms in an Oct 2026 package — not Mali, and not a UI model. Do not plan on DETR for v0. |

**Pros.** Finds controls the tree does not have: icon buttons with no semantics, custom-drawn chrome, some map HUD, game buttons. Caleb can train this. A one-class "clickable region" model is enough for the contract's box; type can be a small head or a rule on aspect ratio plus OCR. Open-vocab (YOLOE) is attractive and is also heavier and AGPL.

**Cons.**

- It re-detects boxes the OS already computed, and it is worst exactly where labels are scarcest.
- If the training boxes come from accessibility, the model learns to imitate accessibility and fails on the blind set. That is the central data bug (section 4).
- No label, so a box is not yet a tap. OCR or the tree has to be fused anyway.
- Dense text screens (Spotify, mail, chats) produce a box per row. Choosing the row is `vision_step`, and a detector that then "taps the top box" is a bug.
- 1080×2400 → 640 input misses small icons **[est]**. Tiled inference multiplies latency. A better compromise is one 640 pass plus a second crop of the top bar and the map-chrome corners, only when those crops are not already explained by the tree.
- False positives on glyphs, album art, map labels, and decorations. Confidence is not calibrated out of the box; τ needs a live set.
- Thermal: a UI task is many frames. p95 under sustained Mali load is the number that matters, and it is unmeasured.

**Latency on G2/G3.** **Unmeasured.** Planning ranges, marked as estimates and not as benchmarks:

- Nano CNN, INT8, 640, Mali GPU, warm: hope is "Pixel 10 did ~45 ms, G2 is slower," so **[est] 40–150 ms**. The 120 ms gate is plausible and not promised.
- Same model, CPU only: Xeon YOLO26n was already 39 ms; a phone efficiency core is slower. **[est] may miss 120 ms p50** once pre/post and thermal are included. GPU delegate is the plan, CPU is the fallback.
- DETR nano as published for phones: **not in budget** until someone shows a Mali trace under 120 ms.
- Anything with a captioning transformer in the same pass: out of budget (see F and G).

**Maturity.** CNN YOLO export is the most mature custom-model path in this document. DETR mobile export is not mature on Mali. UI-specific public checkpoints exist (OmniParser icon detector) and are desktop/web-heavy.

### D. Segmentation (SAM and cousins)

**What.** Promptable masks (SAM, MobileSAM, EdgeSAM, EfficientViT-SAM, FastSAM). Useful when the object is an irregular region.

**Pros.** Can hug a non-rectangular control or a map pin if you already have a point. Apache-2.0 for Meta SAM **[verify license header on the checkpoint you actually ship]**.

**Cons.** UI controls are axis-aligned rectangles. A mask is a slower way to get a box. "Segment everything" is a grid of decoder calls; the efficient-SAM survey shows that grid exploding into seconds. EdgeSAM's headline is **38.7 FPS on an iPhone 14** for encoder+one prompt, which is a different NPU and still an encoder you must run before you know where to prompt. MobileSAM's encoder alone is **73–126 ms on a Snapdragon 8 Gen 3**, a chip we do not have. On G2 this is the wrong tool.

**Latency.** Not a candidate for the 120 ms full-frame pass **[est: over]**.

**Maturity.** Research and photo products. Not a UI detector.

### E. OCR-first

**What.** Detect text lines, recognize them, then treat a line as a label and grow a box around it (or snap to a nearby container) to invent a control. PP-OCRv5 mobile (Paddle, Apache-2.0) is the current open model; the plan already names PP-OCR vs ML Kit for `ocr_commit` (S-23). ML Kit's bundled recognizer is a Google binary and a Play-services-adjacent dependency; it loses on a no-Play image even if the bundled variant sometimes runs without GMS **[verify]**. Tesseract is Apache and weaker on UI text.

Paddle's own PP-OCRv4 end-to-end figure is **76 ms on an Intel CPU**, not a phone. A 2026 mobile-OCR writeup (different engine, Snapdragon-class) still landed around **400 ms** of OCR after optimization. Full-frame phone latency for PP-OCRv5 is **[verify]**.

**Pros.** Labels, which pure detectors lack. Directly serves `ocr_commit` (Send / Pay / Confirm), including on an unlabeled-looking button that still has glyphs. Apache. Can run on a crop (the node, or the bottom sheet) and then it is cheap. Language coverage of PP-OCRv5 mobile includes English and several CJK scripts in one rec model.

**Cons.** Icon-only controls are invisible. Text inside a map is not a control. Growing "a button box" from a text line is a heuristic and will eat neighboring labels. Full-frame OCR every step will blow 120 ms **[est]**. Recognition is the expensive half; detection of text regions is the cheap half.

**Latency.** Full frame: **[est] over 120 ms** on panther until S-23 says otherwise. Per-crop on a handful of nodes: **[est] can fit**, and should be the design. Commit-button OCR is allowed to be slower than `control_detect` because it gates speech, not every tap; still keep it on-device.

**Maturity.** Production. Paddle's Android demo is CPU and real. Wiring it into LiteRT (community PP-OCRv5 Android ports exist, Apache) avoids a second runtime.

### F. Classical CV

**What.** Downscale, edges or MSER, axis-aligned rectangles, template match for a dozen system icons (back, overflow, close, mic, search), color-blob for a FAB.

**Pros.** No license, no thermal surprise, inspectable failures. **[est] under 20 ms** on a 360-wide frame. Good as a *proposal prior* and as a unit-test oracle on the fixture app (M3-UI-04). Templates work for SystemUI chrome that rarely changes shape.

**Cons.** Themes, dynamic color, and custom iconography break templates. Contours fire on every card and every album cover. Not a Top-N strategy. Do not staff a quarter on this.

**Maturity.** Ancient and solved. Useful as 50 lines, not as an architecture.

### G. Small grounding VLMs

**What.** A model that takes the screenshot and the utterance and returns a point or a box: Florence-2, PaliGemma-class, SmolVLM, Moondream, UI-TARS-small, ShowUI, Ferret-UI, ScreenAI-style. This collapses `control_detect` and part of `vision_step` into one on-device call.

**Pros.** Right *task* shape: "the Start button" rather than "here are 40 boxes." Open weights exist for several of these. ScreenSpot (mobile+desktop+web click accuracy) shows specialist GUI models far ahead of generic small VLMs: published comparison points include SeeClick 53.4, CogAgent 47.4, UGround-7B 73.3, OS-Atlas-7B 82.5, UI-TARS-7B 89.5. Those are 7B-class models. They are cloud/`vision_step` references, not actuator models.

**Cons.** Phone measurements of sub-1B VLMs are seconds, not milliseconds. One 2026 on-device paper: a 500M-class VLM at **27.1 s/frame on a Galaxy A55 CPU**. Another: best-of-class small VLM generation **10.6 s on a Pixel 9**, **7.4 s on an S23**. Even a generous 10× from a better runtime is still ~1 s, ~8× over budget, and it occupies RAM the actuator does not have spare (hundreds of MB to >1 GB). Actuator APK size and cold start get worse. No network does not mean "run the cloud model locally."

**Latency.** **Out of `control_detect` by an order of magnitude or more** on G2/G3 **[est, grounded in the phone numbers above]**. Revisit only as a *session-side* offline fallback when Wi-Fi is down and the tree failed, with a spoken estimate (P7), and only if a future spike on panther shows a sub-second point-grounder. That spike is not v0.

**License.** Per model (Florence-2 is MIT; many GUI models are research-licensed). Check before bundling.

**Maturity.** Exploding as a cloud technique. Not mature as a 120 ms phone component.

### H. Set-of-Mark (SoM)

**What.** Paint stable ids on candidate boxes, ask the vision model "which id?", map the id back to a box. Microsoft Research, 2023. The marks can come from the tree, from the detector, or from OCR lines.

**Pros.** Turns a coordinate regression (which vision models miss) into a multiple-choice question. A 2026 single-site measurement (one catalogue page, one model) went from **0/15** raw-coordinate clicks to **15/15** with marks, at +56% prompt tokens. That is an anecdote with a real mechanism behind it, not a benchmark. For VOS it is a **`vision_step` prompt format**, and it is also how the on-device stage should talk to the cloud: send marks + the pruned tree, not a naked screenshot. Ambiguous Spotify rows are exactly this.

**Cons.** Does not detect anything by itself. Bad proposals in, bad menu out. Too many marks and the screenshot becomes unreadable; cap and crop. Marks must be stripped before any image is stored. Cloud latency stays the cloud budget (plan: spoken estimate, ~1.5 s p50 target on good Wi-Fi, network excluded from the local budget).

**Latency.** Painting is **[est] <5 ms**. The model call is `vision_step`.

**License.** The technique is a paper. No code license if we draw numbers ourselves.

**Maturity.** Standard in GUI-agent demos. Correct place in this architecture is the escalation format, not the on-device detector.

### I. Screen-diff and temporal tricks

**What.** Absdiff or damage rects between frames. Optical flow or phase correlation on the map region to see pan/zoom. Short-delay frame to catch press highlight (B6).

**Pros.** Serves `ui.oracle` directly: "the map moved," "a dialog appeared," "nothing happened." Lets the next detect run only on the dirty window (`getWindows()` diff is even cheaper than pixels). Press-highlight localizes custom controls after the fact. Interaction logs become labels (section 4). For M3-MAP, the oracle is "camera changed," which a diff measures without understanding the map.

**Cons.** Not a list of controls. A moving map diffs *everything* and must be masked to chrome vs canvas. Animation (spinners, shimmer) looks like change. Two frames cost two screenshots, and the screenshot API is rate-limited.

**Latency.** Downscaled absdiff **[est] 2–15 ms**. The wait for the UI to settle is the plan's 300 ms, not the diff.

**Maturity.** Trivial and reliable if the question is "did it change," unreliable if the question is "what control is this."

### J. Hybrids

Everything above is a stage, not a product. The combinations that make sense:

1. **Tree → residual pixels → OCR on unlabeled boxes → SoM cloud.** Recommended. Section 3.
2. **Pixels first, tree as a label feature** (rasterize the tree into an extra channel, or fuse boxes by IoU). This is the "YOLO, but with accessibility" design. It spends the 120 ms even when the tree was already sufficient, and it still needs the tree to be present at training time. Use fusion as a *feature of the residual model*, not as a reason to run the model every frame.
3. **OS snapshot (B2) → pixels only inside `pixel_only` regions.** Best steady state if Tier F is ever opened for this. v0 does not wait for it.
4. **OCR-first for commit buttons, tree-first for everything else.** `ocr_commit` stays specialized and conservative. Do not merge it into the general detector's τ. A missed Pay button is a safety bug; a missed icon is a retry.

---

## 3. Top three architectures, a v0, and the disagreement

### 1. Tree-first cascade (build this)

```text
screen.capture
  windows + a11y nodes + downscaled pixels
        │
        ├─ nodes with trustworthy bounds  → proposals
        │     type from actions, label from text/desc
        │     confidence high if the label matches the goal
        │     and the node is uniquely the target
        │
        ├─ parent node too coarse (a row) → OCR lines inside it
        │     or residual detector inside the parent crop
        │
        ├─ region flagged blind
        │     (GL, empty tree, known-bad package, Flutter-if-spike-fails)
        │     → small CNN on that crop or on the frame
        │
        └─ still ambiguous, or the goal is semantic
              → redact, paint SoM ids, vision_step
```

Run the CNN only on the blind fraction. Most Settings / Signal / dialog steps never load it, which is how M3-UI-05 (≥60% on-device, and most of those with no upload) stays easy. Maps look-around does not need the detector to understand the map: chrome (search, close, layers) comes from the tree or the crop detector; the canvas is a gesture target; the oracle is a diff plus OCR of whatever labels became visible.

**Why this wins.** It spends model time where the OS is ignorant, and it uses the OS where the OS already did the hit-test. It degrades: tree only, then tree+OCR, then +detector, then cloud. Each stage has a license that is Apache/MIT/platform if the detector is chosen from the permissive column.

### 2. OS snapshot service (B2) plus pixels only for GL

Same contract, richer tree: View bounds, Compose semantics even when hidden from accessibility, WebView element boxes, `pixel_only` masks. The residual CNN trains on those masks and on custom-draw fixtures, not on the whole screen. Add the click ring buffer (B3) from day one of that patch so labels are free.

**Why it is not v0.** Tier F cost, rebase cost, and we do not yet know the blind rate. If a spike shows that Maps chrome, Spotify, and Signal are mostly in the Tier S tree, this patch is not worth one of the five Tier F slots. If the spike shows Compose-without-semantics and WebView dominate failures, this patch is worth more than any model.

### 3. Specialist pixel detector as the primary (the obvious YOLO answer)

Fine-tune a small one-class or few-class detector on phone screenshots. Fuse accessibility boxes afterward by IoU (trust the tree on conflict unless the tree's box is huge). OCR for labels. Cloud SoM when the top boxes are the same type.

**When this is the right primary.** Only if the Tier S blind-rate spike is ugly (most tap targets absent or bounds wrong on the M3 set). Then the detector is the product and the tree is a feature. I do not expect that result on Settings, Signal, and SystemUI. I do expect it on map canvas, some games, and badly built WebViews.

### v0 (what to bind in the ModelRouter row first)

Do not block M3 on a trained network.

1. **`control_detect` v0 is an accessibility adapter.** Same JSON contract. No weights. Confidence rules written down and unit-tested. Unlabeled clickables are emitted at medium confidence so the Operator does not tap them blindly when several match; a unique content-description match may clear τ.
2. **Spike S-UI-TREE before any training spend:** 50 cold screens each of Settings, Signal, Vanadium, Spotify, Maps guidance, Organic Maps, one Flutter app, one game, one custom-view app. For each spoken target, record whether a node exists, whether its bounds contain the true tap point, and whether the bounds are tighter than the parent row. Publish the blind rate. That number chooses between architecture 1 and architecture 3.
3. **Spike S-UI-LAT in parallel, on panther and akita, GPU and CPU, warm and after 2 minutes of loop:** one permissive nano CNN (YOLOX-Nano or a MIT YOLO-family reimplementation — not Ultralytics unless Caleb accepts AGPL on the actuator), INT8 LiteRT, inputs 320 and 640, plus a top-bar crop. Gate is the plan's 120 / 250 ms, *including* preprocess and NMS, *excluding* the screenshot. Also log thermal p95.
4. **OCR v0 is `ocr_commit` only** (S-23), PP-OCRv5 mobile via LiteRT, Apache. General label OCR waits until the tree spike shows unlabeled-but-textual controls are common.
5. **`vision_step` prompt v0 includes SoM ids and the pruned tree**, even while the on-device side is tree-only. That is a session change, not a model.
6. **Diff oracle v0:** downscaled absdiff plus window-list diff, used by `ui.oracle` and by the auto-label log. Not a detector.

A detector checkpoint replaces the "blind region → empty" branch when spikes 2 and 3 say it is both needed and fast enough. Until then the honest behavior is: tree hit → tap; tree miss → cloud if allowed, else `NEEDS_NETWORK` / retry, never a fabricated box.

### Where this disagrees with "just fine-tune YOLO"

I disagree that it is the architecture. I agree it is the right *residual class of model*, with these corrections:

- **The OS already outputs the contract** for a large class of controls, with better type and label than a detector will learn. Starting from weights throws away the only zero-latency, zero-license source.
- **Training on accessibility boxes teaches the model to copy accessibility.** The interesting error is the complement of the training set. A YOLO trained that way will look strong on a random screenshot benchmark and fail on the screens that made us want a model.
- **120 ms is a Mali budget, not a T4 budget and not a Snapdragon NPU budget.** RF-DETR's 2.3 ms and Qualcomm's 13 ms do not transfer. DETR's deformable ops have already embarrassed a LiteRT phone port. Prefer a tiny CNN, and measure it on panther before naming it in the plan.
- **AGPL is a real constraint** if the implementation is Ultralytics YOLO11/26. Caleb's existing fine-tuning experience is an asset; the weights pipeline should be Apache or MIT (YOLOX, PP-YOLOE, DAMO-YOLO, or a MIT YOLO reimplementation, or OmniParser `icon_detect_v3` as an initialization only — it is YOLOv9-E, likely too heavy to ship as-is). OmniParser's Florence captioner does not come on device.
- **Detection is the easy half of Spotify and of Maps.** Every row is a control; the map is not a control. The product failures in M3-SPOT and M3-MAP are semantic choice and the decision to pan, which live in `vision_step` and in the explore policy. A better YOLO does not pass M3-MAP-02.
- **`ocr_commit` must not be a class in the same head** with a shared τ. Safety recall and ordinary tap precision want different errors.

What I would not do: skip a pixel model forever, ship a 7B grounder in the actuator, or parse SurfaceFlinger into buttons.

---

## 4. Data and training

### What "good" labels are

A label is a box that a tap in the center activates, plus a type, plus text if any. Accessibility boxes are a *subset* of that, biased toward apps that already shipped semantics. View-hierarchy boxes (B1/B2) are a bigger subset and still miss canvas. The labels that cover the blind set come from interaction, not from the tree.

### Sources, in the order to actually use them

1. **Public pretrain, then leave it.** RICO (2017, mobile screenshots + view hierarchies) is old and still the largest box-level mobile UI set. Widget Captioning / Screen2Words add text. Android-in-the-Wild and AndroidControl are trajectories: good for the *policy* of when to scroll, weak as tight box supervision. ScreenSpot's mobile split is a grounding eval, mostly not a detector training set, and it must not be the M3 gate (the gate is live apps). Use public data to teach "rectangular tappable things" and then forget its class names.

2. **Dev-phone silver labels from the full View tree**, including non-accessible views, captured with `DUMP` during normal use of the M3 apps. Store package, version (for diagnosis, not for pinning), screenshot, boxes. This is allowed to be manual and small.

3. **The actuator's own log, which is the dataset that matters.** Every `ui.gesture` already has a before-frame, a point, and an after-frame, plus the oracle. Turn that into labels:

   - **Point supervision.** The tap point is a positive location. Any proposed box that contains it and after which the oracle fired is a positive. Boxes that contained it and after which nothing changed are hard negatives (dead pixel, or the wrong overlapping node).
   - **Press-highlight.** Grab a frame ~one vsync after the gesture **[timing verify]**. The small dirty blob *inside* the before/after stability is often the pressed control. That blob is a box for custom draws that have no node.
   - **Dialog birth.** A new window in `getWindows()` is a positive region; its nodes (or the diff) label the new controls. This is how the model sees "the button that appears after tap" without a human.
   - **Owner finger vs injected gesture.** S-09 separates them. Owner taps are gold and cover apps VOS has not automated yet. Injected taps are silver and biased toward what the current policy already tries. Keep the two sets separate so the model does not only learn to confirm itself.
   - **Do not auto-tap to mine labels** on irreversible controls. Mining is passive: log what was already done for a goal, or what the owner did. Commit buttons are labeled by the fixture app (M3-UI-04) and by OCR text, not by a crawler that presses Pay.

4. **Synthetic, narrow.** Inflate a few hundred layouts (XML and Compose) with known bounds, random Material colors, random strings. Good for the taxonomy head and for not forgetting what a switch looks like. Bad as the only data: production apps do not look like framework samples. Render at panther resolution and at a downscale so the input pipeline matches.

5. **Hard-negative mining from failures.** M3 and field runs that fail the step oracle become the next training epoch, especially frames where the tree was empty or the tapped box was the whole row. This is the anti-circular set. Weight it higher than RICO.

### Self-supervision that is actually self-supervision

- **Persistence.** A control that stays put across frames while the screen is still is the same instance. Track it. No new information, but it stabilizes boxes and exposes flicker.
- **Tap → effect as the loss.** Point-to-box: among proposals containing the point, the one whose tap changed the oracle gets the credit. This can fine-tune a detector without boxes, the same way point-supervised detectors work, if we ever need it. It can also just re-rank a frozen detector.
- **Masked reconstruction and contrastive UI encoders** do not directly emit the contract. Skip them until a detector exists and is short of data. The interaction log is the supervision.

### Training mechanics

- Off the phone. The actuator cannot upload and should not train. A dev pull of on-device logs (owner-consented, `FLAG_SECURE` frames dropped, IME masked with the same redactor as `vision_step`) goes to the workstation. Nothing in this loop is a runtime network path.
- Initialize from COCO or from a permissive UI checkpoint if one is license-clean. OmniParser `icon_detect_v3` (MIT, 2026) is a reasonable initialization *if* it can be distilled into the tiny CNN. Do not ship the E-size model because the weights exist.
- Class set for v1 of the residual model: one class, `control`. Type comes from the tree when it overlaps, else from rules (editable-looking / switch-looking can wait). A five-way head is optional and will be wrong often enough to regret.
- Input: the blind crop when the caller has one; full frame at 640 only as fallback. Evaluate small-icon recall separately (bar icons, map buttons). If 640 fails that slice, add the bar crop rather than jumping to 1280.
- Calibrate confidence on a live holdout (temperature on one scalar) before τ is chosen. Report reliability, not only mAP.
- Eval split is **by app and by failure mode**, not by random frames: tree-complete, row-merged, tree-blind, GL, secure-window. mAP on tree-complete screens is a vanity metric. The number to beat is: tap-point-in-true-control on the blind slice, at the latency gate, on current app builds, with version logged and the APK unpinned.

### Auto-label loop (concrete)

```text
after every gesture:
  before, point, after, windows_before, windows_after, oracle
  if secure: store metadata only
  else:
    diff = absdiff(downscaled)
    if new window: label = nodes in the new window (silver)
    else if small stable blob around point: label = blob (silver, custom-draw)
    else if a11y node contained point and oracle passed: label = node (silver)
    else if oracle failed: hard negative at point
  owner-finger events (S-09): same, marked gold, never mixed into the silver weight without a flag
```

Review a sample weekly before it trains. Bad oracles (map tiles loading, shimmer) will otherwise become boxes.

---

## 5. Risks and open questions

**Risks**

- **The 120 ms gate is tighter than the blog-post ecosystem implies.** Published "real-time" numbers are T4 TensorRT or Qualcomm NPU. Our accelerator is a Mali GPU on a 2022/2023 SoC, shared with the compositor while we also take screenshots. Thermal p95 can fail a model that passed a cold microbenchmark.
- **Screenshot rate limiting** can dominate detect latency. If S-10 shows a floor near one capture per second, no detector matters until capture is fixed (batch the tree with the pixels; reuse a frame for detect + oracle; prefer `takeScreenshotOfWindow` so the accessibility overlay is not in the bitmap).
- **Tree/model circularity.** Shipping a detector trained on the tree, then quoting its accuracy on tree-labeled tests, will bless a model that does not help.
- **Row merge.** The most likely production miss on apps that "have accessibility." If v0 taps the center of a list item, it will tap titles when it should tap overflow or play. Medium confidence on fat nodes is mandatory.
- **Flutter semantics gate [verify].** A service that is not touch-exploration may see an empty tree. Forcing exploration changes the UI (TalkBack-like focus). Measure before designing around it.
- **Android 17 Advanced Protection** (reported Oct 2026) limits `AccessibilityService` to apps Google has classed as accessibility tools, when that mode is on. Tier S on a future stock phone could lose the tree. The VOS image can allowlist its own service; Graphene may not implement Play-vetting the same way **[verify]**. Do not depend on a Play classification.
- **AGPL contamination** through Ultralytics training code, and GPL through upstream YOLOv9. Easy to do by habit.
- **DETR export trap.** Choosing RF-DETR because it leads COCO and then discovering deformable attention will not run on Mali.
- **GL and maps.** No tree and no detector will read a pin that is paint. Look-around is gestures + diff + OCR of labels + cloud. Holding `control_detect` responsible for M3-MAP-02 will produce a doomed model project.
- **False tap from a confident wrong box.** τ on an uncalibrated score will either spam the cloud (M3-UI-05 fails) or tap through a payment row (trusted path must still speak). Detector confidence never authorizes a commit; `ocr_commit` does.
- **Privacy of the training log.** Screenshots plus a click ring buffer are a complete record of phone use. Actuator-local, encrypted, owner-export only, secure windows excluded, IME masked. A Tier F click logger that is on by default is a surveillance feature. Default it to "on during a VOS goal and for N minutes after an owner opt-in for data collection."
- **Hidden semantics.** A Tier F walker that returns nodes the app marked hidden can see more than a screen reader. Decide explicitly; default to accessibility-visible plus `pixel_only` masks, and elevate hidden nodes only for packages in an owner policy.
- **License and binary size of ML Kit** on a no-Play Graphene image.
- **Two devices.** A model timed on panther can miss on akita or the reverse (different Mali, different thermal). The gate is panther; akita needs the same spike before it is "supported."
- **OmniParser imitation.** Copying the full icon-detect + Florence caption pipeline will miss 120 ms by a wide margin (their own v2 figure is 0.6–0.8 s on A100/4090 for the *pipeline*). Steal the idea (detect regions, attach text, let the big model choose), not the checkpoint layout.

**Open questions**

1. On the real M3 apps, what fraction of tap targets are a tight accessibility node, a fat parent, or absent? (S-UI-TREE.) This single number picks the architecture.
2. What is INT8 GPU p50/p95 for one permissive nano CNN at 320, 640, and a bar-crop, on panther, after warmup and after heat? (S-UI-LAT.)
3. Does a non-TalkBack accessibility service receive Flutter and WebView nodes on current Graphene?
4. Can the Tier P actuator hold `DUMP` and call a hierarchy dump without a framework patch, given avbroot keys?
5. What is the true `takeScreenshot` minimum interval on the Graphene build we will ship?
6. Is Caleb's current YOLO training stack Ultralytics? If yes, the license decision is now, not at release.
7. For `ocr_commit`, does PP-OCRv5-mobile on Mali hit S-23's ≥95% word accuracy on the fixture, and at what latency? Does the bundled ML Kit model run with zero GMS and an acceptable license?
8. How stable is press-highlight timing across Material, Compose, and custom views? Is it a label source or a coin flip?
9. Should τ be one global scalar or per type (high for toggle, lower for icon, never for commit)?
10. Does Organic Maps expose more of the map as nodes than Google Maps, and does that change the look-around design per app?

**Bottom line.** v0 is the accessibility tree wearing the `control_detect` contract, plus a diff oracle, plus SoM on the cloud step, plus two spikes. The pixel model is a small permissive CNN aimed at blind crops, trained on interaction labels so it is not a photocopy of the tree, measured on Mali before it is allowed into the 120 ms row. The OS patch that dumps View and Compose bounds is the right long-term hammer if the spikes say the tree is the bottleneck. A fine-tuned YOLO as the whole answer is the wrong shape: wrong license if it is Ultralytics, wrong accelerator if the spreadsheet assumed a TPU or a Snapdragon NPU, and wrong error if the labels came from the API it is supposed to replace.

---

## 6. Comparison with Claude's report

Compared against `research/UI-DETECT-RESEARCH-2026-10-12.md` after sections 1–5 were already written. Those sections were not revised. While writing this section I opened the two LiteRT cards Claude uses for Pixel 8a latency, because that was the main numerical gap. I did not re-check the rest of Claude's bibliography.

### Agreements

We landed on the same system, described from different ends:

- The slot proposes boxes. It does not choose among lookalikes. Spotify and any dense list are a ranking problem. Cloud `vision_step` should return a Set-of-Mark id, and the phone taps that mark's center.
- Accessibility is the source of labels, roles, actions, and exact bounds. A pixel model covers what the tree does not. OCR runs on crops, and it should share the S-23 PP-OCR stack. `FLAG_SECURE` stays tree-only.
- Tensor G2/G3 do not get the LiteRT Tensor NPU path (G5/G6 only, beta, Play feature delivery). Ship the GPU delegate. NNAPI is deprecated and is an experiment, not a plan. Bundle LiteRT; do not use Play-services TFLite.
- A grounding VLM (even 0.8–2B) is seconds on a phone and belongs, if anywhere, as an offline `vision_step` in the session, with a spoken estimate. Not in `control_detect`.
- Ultralytics YOLO11/26 is AGPL-3.0, including trained weights by Ultralytics' own account. YOLOX and a MIT YOLOv9 reimplementation are the permissive swaps. OmniParser's full pipeline is too slow; `icon_detect_v3` (MIT, YOLOv9-E) is a desktop teacher, not the on-device model.
- DETRs are second, not first. RF-DETR needs a split graph because `grid_sample`, 5D tensors, and TOPK do not stay on the Mali delegate. Pre/post can dominate the model. The card I re-read matches Claude: Pixel 8a, CompiledModel GPU, graph A ~22 ms + graph B ~5 ms, about 100 ms end to end, Apache-2.0. The stock TFLite GPU delegate **did not run** those graphs. CPU XNNPACK was 375 ms + 80 ms.
- Eval stays on current apps, version logged, APK unpinned. Split by app, not by frame. Measure under Maps plus heat, not a cold microbenchmark. Calibrate τ. Keep a margin between the top two candidates before an on-device tap. Irreversible taps still go through Announcer. Training frames stay on UStor2, never a cloud labeler, never `FLAG_SECURE`.
- Claude's S-35 / S-36 / S-37 are the same spikes as my S-UI-LAT, S-UI-TREE, and the auto-label loop.

The YOLOX-Nano card also checks out: 416², FP16, 2.2 MB, Apache-2.0, Pixel 8a, `benchmark_model` OpenCL **24.0 ms**, 482/482 ops on GPU. That is model time only. Decode and NMS are on the host and not in the 24 ms. XNNPACK **declines** this FP16 graph. COCO AP of this checkpoint is 25.8, so it is a speed witness, not a UI model. My section 3 range of **[est] 40–150 ms** for a nano CNN at 640 on a G2 was vaguer than it needed to be: on a G3, a clean nano CNN at 416 is inside the gate before postprocess. I still would not call the *pipeline* on panther, at a portrait input, under Maps, "inside with margin" until S-35.

### Disagreements

**What "v0" means.** Claude's v0 is the fusion running a fine-tuned small detector every frame, with a11y-only as the fallback that puts M3-UI-05's 60% at risk. Mine is the a11y adapter shipping first, detector empty until the blind-rate spike says it is needed and the latency spike says it fits. Claude's own risk table already says to skip the detector when a11y resolves the step, for thermals. That skip should be the default control flow, not a fallback. The 2–3 week training estimate is reasonable *after* S-36, and it is the wrong thing to block the contract on. DailyDroid (which Claude cites: tree-only did most of the work, screenshots helped at the margin) supports sequencing it this way.

**"Unlabeled icon" is not the same as "no node."** Claude's bottom line says the detector covers unlabeled icons. LabelDroid, which Claude cites (>77% of apps had unlabeled image buttons; a separate study found ~93% of FABs without a content description), is about missing *names*, not missing boxes. Those nodes still give a bbox and `clickable`. v0 can tap them when the goal matches a unique role or there is only one plausible target. v0 cannot *name* a row of nameless icons. That is OCR, an icon head, or cloud SoM — not proof that a detector must run to find the box. Claude's merge rule in the body (a11y-only boxes are kept) is more careful than the summary. I under-weighted the naming gap in sections 1–5.

**Class head.** I said one class, `control`, and let the tree supply type. Claude wants about ten classes, including `list_row`, `tile`, `tab`, and `text`, aligned with CLAY. Claude is right that `list_row` / `text` / `icon` earn a place: they are what makes the giant-merged-node rule work (children inside a fat parent, text lines for the crop recognizer, overflow icons that must not be the row's tap point). I was too aggressive. I still would not ask the detector to distinguish switch vs checkbox vs radio; that is a11y state. `link` will be starved.

**Input shape.** Claude's 448×960 portrait letterbox, chosen so a 48 dp target stays ~50 px at ~2.6× density, is a better default than the 640-square COCO habit I wrote down. I would still spike a bar/corner crop: many real icons are smaller than 48 dp, and 0.42× scale will drop them. Claude's "fast" 384×832 variant is the right thermal knob.

**How hard to lean on public mAP.** Claude's planning estimate of ~85–95% interactive recall on seen apps, and ~95%+ with fusion, is labeled **[est]** and sits next to Apple's 71.3% mAP on 77k curated iOS screens and OmniParser's ~0.49 ScreenSpot-mobile target recall. I would not put 95% in a plan. Apple Screen Recognition is the right existence proof (a small on-device UI detector shipped, in real time, on a phone). It is not a forecast for GrapheneOS Spotify.

**OS changes.** Claude stays inside the accessibility service the actuator already has, plus `ACTION_CLICK` when a node matches. That is the right v0, and it respects the plan's small Tier F budget. It misses mechanisms that are available because this is our phone:

- A privileged View dump (`DUMP` / `dumpsys activity top`) sees views with `importantForAccessibility=no`. It still does not see Compose children.
- A Tier F snapshot that also walks `SemanticsOwner` and WebView element rects is the patch to write if S-36 says the misses are "drawn but not a node," not "node without a name."
- A `performClick` / gesture ring buffer, and a frame taken while the ripple is up, label custom-drawn controls that no teacher model has seen. Claude's trajectory-positive (the node under a tap that passed the oracle) does not fire when there is no node.
- SurfaceFlinger damage and the window list are how you avoid re-running detection on a static frame. Claude crops logically (only when a11y fails) but does not mention compositor damage.

I over-weighted that patch relative to Tier F scarcity. Claude under-weighted it to zero. The click log and the press frame do not require a framework fork if the accessibility gesture path plus a second screenshot can see the ripple; the SemanticsOwner walk does.

**Maps.** Claude treats the map canvas as "detector + cloud." I treat look-around as gestures, a diff oracle, OCR of labels that appear, and cloud for the semantic question. A detector will not read a pin that is GL paint. Holding `control_detect` to M3-MAP-02 will waste the model.

**`ocr_commit`.** Claude folds the recognizer into `control_detect`. I want the commit check on its own threshold. We agree Announcer still speaks. The separate τ is the part I would not drop.

### What Claude covered that I missed

- The Pixel 8a cards above, and the practical consequence: ship a separate INT8 or FP32 CPU artifact because XNNPACK drops some FP16 graphs. "CPU fallback of the same FP16 file" is not a fallback.
- The public mobile corpus and what each is for: CLAY (schema), MUD (modern Android), MobileViews (MIT, large, weak view-hierarchy labels), VINS, AMEX, AndroidControl, WebUI / ScreenParse v2 as pretrain only. Section 4 of this note is thin by comparison. RICO alone is not the pretrain story.
- ScreenParse (ICML 2026, 55 web classes), Salesforce GPA-GUI-Detector, racineai CU-1, TargetFinder. Useful as "everyone else is web/desktop and AGPL-tainted; do not ship them," which I did not inventory.
- Named small VLMs for a later offline `vision_step`: Holo-3.1-0.8B, Qwen3-VL-2B, MAI-UI-2B, Ferret-UI Lite. Florence-2 is hundreds of milliseconds to seconds depending on decode length, not merely "too big."
- Contract extensions that do not break the plan: `label_src`, `sources`, `a11y_node_ref`, `actions`, and preferring `ACTION_CLICK` / `ACTION_SET_TEXT` over a coordinate when the node matched.
- Bench layers L0 (perception) / L1 (step grounding) / L2 (task), tap-hit instead of mAP as the primary, wrong-tap rate as a safety gate, Wilson intervals with N≥3, re-run within a day of an app update, seen vs unseen apps. I stated the metrics more loosely.
- Training details I skipped: no mosaic, almost no horizontal flip, Material You hue, font scale, RTL, IME on/off; isotonic calibration of the *fused* score; human review only of teacher-vs-a11y disagreements.
- Adversarial `contentDescription` (app-controlled text, quoted, never instructions) and stale nodes under dialogs (`isVisibleToUser` plus window layer).
- hardened_malloc / MTE as a reason the GPU delegate itself needs a spike (S-24), not just the model.
- A concrete ModelRouter row: FP16 GPU, INT8 CPU, and an a11y-only id. I described the policy and did not write the ids.

### What this note covered that Claude missed

- OS surfaces past accessibility: View-tree dump vs Compose semantics vs WebView DOM vs GL `pixel_only`, hit-test grids (and why they are worse than a walk), SurfaceFlinger damage, ripple/press as a label, and the privacy shape of a system-wide click log.
- Why a detector trained on accessibility boxes fails on the complement of its training set, and the press-blob / owner-finger vs injected-gesture split that labels that complement. Claude has trajectory positives and a disagreement queue; the circularity warning is softer there.
- SAM-style segmentation as an explicit reject (masks are a slow way to get rectangles; MobileSAM's encoder is already 73–126 ms on a Snapdragon NPU we do not have).
- Classical contours and icon templates as a few dozen milliseconds of proposal prior, not an architecture. Claude points at UIED only as history.
- D-FINE's community LiteRT port at ~450 ms (and a graph at ~2 s) as a second data point that deformable DETRs are not a Mali plan. Claude's RF-DETR card is the better evidence; this is corroboration.
- Pixel 10 YOLO26n LiteRT ~45 ms GPU as a *newer chip* reference, so a G2 estimate cannot be copied from a 2026 flagship table.
- Screenshot `INTERVAL_TIME_SHORT` as its own threat to the 80 ms capture budget. Claude includes capture inside S-35's pipeline number, which is the right measurement, and does not call the error out.
- Android 17 Advanced Protection, reported October 2026, restricting `AccessibilityService` to Google-vetted accessibility tools when that mode is on. Tier S on a future stock phone can lose the tree. The VOS image can allowlist itself. **[verify]** against Graphene's actual policy. Claude's report is dated the day before this one; the public writeups were already a few days old.
- Do not crawl irreversible controls to mint labels.

### What I would change after reading Claude

Keep tree-first sequencing. Adopt Claude's portrait input, the `list_row` / `icon` / `text` classes, the fused-score margin, `ACTION_CLICK` when matched, the L0/L1/L2 bench, the public-data map (licenses verified before any distributed checkpoint), and the separate CPU artifact. Treat 24 ms on the 8a as evidence a nano CNN *can* fit, and still gate panther on the full pipeline. Leave the Tier F semantics walk on the shelf until S-36 says missing nodes, not missing names, are the failures. Do not adopt 95% recall as a planning number, and do not run the detector on frames the tree already resolved.
