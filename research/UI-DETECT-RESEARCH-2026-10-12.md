# On-device UI control detection for VOS (`control_detect`)

| Field | Value |
|---|---|
| Date | 2026-10-12 |
| For | Caleb / `docs/TARGET-PLAN.md` §3.2.1 (ModelRouter), §3.5.1 (hybrid vision), §3.5.2 (`ui.control_detect`), §6 M3 (M3-UI-05, M3-BENCH-01, M3-SPOT) |
| Targets | Pixel 7 `panther` (Tensor G2), Pixel 8a `akita` (Tensor G3), GrapheneOS, no Play services, actuator has no network |
| Status | Desk research. No numbers here were measured on panther or akita. Every latency figure for those two phones is either borrowed from a Pixel 8a measurement by someone else or an extrapolation, and is labeled as such. |
| Tags | **[verify]** = from a secondary source or not confirmed against a primary source. **[est]** = my estimate. |

---

## 0. Bottom line

1. **The v0 pick is a fusion, not one model:** accessibility nodes ∪ a small YOLO-family UI detector, fine-tuned on mobile screens, running FP16 on the LiteRT GPU delegate. Labels come from a11y text first, then a text recognizer run on crops only. The detector covers what a11y misses: unlabeled icons, canvas or Compose surfaces with no semantics, `FLAG_SECURE`-adjacent cases, and stale trees. A11y covers what the detector is weak at: labels, exact bounds, roles, and actions like `ACTION_CLICK`, `ACTION_SET_TEXT`, and `ACTION_SCROLL_FORWARD`.
2. **No off-the-shelf model fits the slot as-is.** Stock COCO YOLO has no UI classes. Every public UI detector found is trained mostly on web or desktop screens, runs at 640–1600 px input, and is often AGPL. The small grounding VLMs (0.8–3B) are good, but they take seconds per frame on a phone, not milliseconds.
3. **Tensor G2/G3 means GPU, not NPU.** LiteRT's Tensor NPU path officially supports only G5 and G6. It is in beta, needs a sign-up, and ships its runtime through Play Feature Delivery. On G2/G3 the TPU is reachable only through NNAPI, which was deprecated in Android 15. So ship FP16 models on the LiteRT GPU delegate (OpenCL), with an INT8 XNNPACK CPU fallback.
4. **A model of this size is fast enough.** Measured on a Pixel 8a (G3) GPU: YOLOX-Nano at 416² takes 24 ms, and RF-DETR-Nano at 384² takes about 27 ms for the model but about 100 ms end to end. My estimate for a nano/small UI detector at 448×960 is about 40–130 ms on G3 and somewhat more on G2 **[est]**. The plan's ≤120 ms p50 / ≤250 ms p95 is reachable with a nano/tiny model if pre- and post-processing stay off the CPU hot path.
5. **Dense lists like Spotify are a selection problem, not a detection problem.** The detector finds every row easily. Choosing the row is (a) on-device label match with a score margin when the owner named the target, or (b) a cloud `vision_step` on numbered marks when the choice needs judgment ("a *new* AI podcast"). The cloud returns a mark id, and the phone taps that mark's center.
6. **License fork:** Ultralytics (YOLOv8/11/26) is AGPL-3.0, and Ultralytics says that covers trained weights too. That is fine if VOS stays private or ships as AGPL-compatible open source. If not, use the same pipeline on YOLOX (Apache-2.0), the MIT YOLOv9 implementation (MultimediaTechLab), or RF-DETR (Apache-2.0).

**Ranked shortlist (details in §8):**

| Rank | Candidate | Role |
|---|---|---|
| **1 (v0)** | **A11y ∪ fine-tuned small YOLO-family UI detector (Caleb's pipeline) + crop-only text recognizer** | `control_detect` default |
| 2 | Same fusion with **RF-DETR-Nano/Small** fine-tuned (Apache-2.0, NMS-free, already proven on Pixel 8a GPU via a split graph) | Swap-in if YOLO recall on dense or nested UIs plateaus, or if the license forces it |
| 3 | **OmniParser-style detector** (OmniParser V2 `icon_detect`, AGPL; or `icon_detect_v3` YOLOv9-E, MIT) | Day-1 zero-training baseline and **desktop teacher for auto-labeling**. Too heavy or too web-tuned to ship as-is. |
| Fallback | **A11y + OCR heuristics only** (UIED-style, no detector) → cloud `vision_step` | Ships even if the detector isn't ready; honest about its gaps |
| Out of slot | Small grounding VLM (Holo-3.1-0.8B, Qwen3-VL-2B, MAI-UI-2B; Apache-2.0) | Possible **offline `vision_step`** backend in the *session* (seconds), not `control_detect` |

---

## 1. What the slot has to do (from the plan)

- **Contract (fixed):** per screenshot, a list of `{bbox|center, type, label?, confidence}`, with `type ∈ {button, field, icon, link, toggle, other, …}` (§3.5.1).
- **Where:** inside `com.vos.actuator`, bundled in the APK, on-device only. The router cannot point `control_detect` at the cloud (§3.2.1, M1-MODEL-02).
- **Budget on panther:** screenshot + a11y ≤80 ms; `control_detect` ≤120 ms p50 / ≤250 ms p95; gesture → settle ≤300 ms (§3.5.1).
- **Success bar:** ≥60% of successful cold steps resolve on-device with no upload (M3-UI-05). UI tasks ≥70% oracle success on **current** apps with no version pins (M3-UI-01, M3-BENCH-01).
- **Environment:** GrapheneOS with hardened_malloc and MTE (S-24), no Play services, so no Play-services TFLite, ML Kit downloads, or AICore/Gemini Nano.

One point shapes the rest of this report: **`control_detect` should propose and localize; it should not decide.** Deciding which control serves the goal is ranking: label match on-device, or Set-of-Mark selection in the cloud. Keeping these apart is what makes dense screens tractable (§4).

---

## 2. Landscape (Q1)

### 2.1 Stock detectors

COCO's 80 classes (person, car, "cell phone", "tv", "laptop", "keyboard", …) contain **no UI controls**. A stock YOLO will at best find "cell phone" in a photo. Every usable option is either trained on UI data or uses a11y metadata.

### 2.2 Pixel-level UI detectors (the class that fits the budget)

| Project | What it outputs | Size / input | License | Fits G2/G3 ≤300 ms? | Notes |
|---|---|---|---|---|---|
| **OmniParser V2** `icon_detect` (Microsoft, Feb 2025) | Class-agnostic "interactable region" boxes. A separate Florence-2 captioner describes icons. | ~20M params (per ScreenParse's table) | **AGPL** (inherits Ultralytics YOLOv8). Captioner MIT. | Detector alone: maybe, at reduced input **[est]**. The full pipeline (0.6 s/frame on A100) does not fit. | Trained on web/desktop screens. On ScreenSpot **mobile**, target recall 0.489 vs 0.584 for an RT-DETRv2 trained on ScreenParse. No control types. |
| **OmniParser `icon_detect_v3`** (2026-07, HF PR #37) | Same task, YOLOv9-E | YOLOv9-E (large) | **MIT** (MultimediaTechLab YOLO base) | No; too large for per-frame on a phone **[est]** | **Best use: a desktop teacher for auto-labeling**, because MIT terms keep pseudo-labels clean. |
| **ScreenParse / ScreenParser** (IBM + ETH, ICML 2026; v2 May 2026) | 55 classes (Button, Text Input, Switch, Toggles, Checkbox, List Item, Tab, Link, Search Bar, …) | YOLO11-L at 1280 px. Also an RT-DETRv2 (43M) and ScreenVLM (316M). | Card says Apache-2.0, but it is Ultralytics-trained, so the AGPL question applies **[verify]** | No at that size. Retrained small, maybe. | **Web-only** data (1.45M screenshots, 25.6M boxes). Very good for **pretraining** and for borrowing a label schema. |
| **Salesforce GPA-GUI-Detector** | Interactive icons and buttons | Ultralytics YOLO, 640–1280 px | Card says MIT. Ultralytics-trained, so ambiguous **[verify]**. | Unknown | No published metrics; desktop examples. |
| **racineai CU-1 / UI-DETR-1** | Class-agnostic clickables | RF-DETR-M at 1600 px | MIT | No at that size | Built from 3,550 Roboflow screenshots. WebClick 70.8% vs OmniParser's 58.8%. 0.82 s/img on a server. |
| **TargetFinder** (2026-07, arXiv 2607.19907) | Desktop widgets | Fine-tuned YOLO; 520 screenshots | Released openly **[verify]** | Plausible | Desktop only; shows a small dataset can beat OmniParser in-domain. |
| **Apple Screen Recognition** (CHI 2021) | 12–13 UI classes for VoiceOver | SSD-MobileNetV1-FPN, ~10 ms, ~20 MB **[verify]** | Closed | n/a | **Existence proof:** 77,637 iOS screens → 71.3% mAP across 13 classes, on-device in real time. This is the shape VOS wants. |
| **UIED** (2020) | Classical CV boxes + CNN classifier + OCR | No training | Repo license unverified | CPU, slow-ish | Old-school; useful for ideas (merging text and graphic boxes), not as the v0 model. |

### 2.3 Grounding VLMs: instruction → point (strong, but out of budget)

| Model | Size | License | Grounding signal | Phone latency |
|---|---|---|---|---|
| **Ferret-UI Lite** (Apple; paper arXiv 2509.26539, press Feb 2026) | 3B | Weights and license **unconfirmed**; Apple's earlier Ferret weights were CC-BY-NC | ScreenSpot-V2 91.6, ScreenSpot-Pro 53.3, AndroidWorld 28.0 | Seconds **[est]** |
| **Qwen3-VL-2B** | 2B | Apache-2.0 | ScreenSpot-V2 ≈86.7 (MAI-UI authors' own run) | Seconds; Qualcomm AI Hub marks it unsupported on mobile chipsets |
| **MAI-UI-2B** (Alibaba Tongyi, Dec 2025) | 2B | Apache-2.0 **[verify]** | GUI agent family; strong on AndroidWorld at larger sizes | Seconds |
| **Holo-3.1-0.8B / 4B** (H Company, Jun 2026) | 0.8B / 4B | Apache-2.0 (HF metadata on 0.8B) | 4B: 71.0% AndroidWorld (BenchLM, Jul 2026 **[verify]**). 0.8B is positioned as a "lightweight local agent". | 0.8B: maybe 1–5 s **[est]** |
| ShowUI-2B, UI-TARS-2B-SFT / 1.5-7B, GUI-Actor-2B, UGround-V1-2B | 2–7B | Mostly Apache/MIT **[verify each]** | Earlier generation (2024–25) | Seconds+ |
| ScreenAI (Google) | 5B | Weights not released | — | — |
| PaliGemma 2 | 3B+ | Gemma terms | General VLM with detect tokens; not UI-specialized | Seconds |
| Florence-2-base | 0.23B | MIT | `<OD>` and `<OCR_WITH_REGION>`. 768² input; autoregressive decode dominates latency. | Hundreds of ms to seconds, depending on output length **[est]** |
| Gemma 3n (MobileNet-V5 encoder) | E2B/E4B | Gemma terms | Google claims 60 fps for the encoder "on Pixel" (runtime unspecified, likely AICore/TPU) | Unavailable on Graphene without AICore; LiteRT-LM path unmeasured |

**Takeaway:** grounding VLMs answer "where is X?" well, but they don't return a cheap inventory of every control, and none fits 120–300 ms on G2/G3. They belong in `vision_step`: cloud by default, maybe an offline local backend in the session later. They don't belong in `control_detect`.

### 2.4 UI datasets

| Dataset | Content | Classes | License | Use for VOS |
|---|---|---|---|---|
| **Rico** (2017) | 66k+ Android UIs + view hierarchies | 25 element categories | CC BY 4.0 per mirror **[verify]** | Pretraining only; designs are dated |
| **CLAY** (Google, CHI 2022) | 59,555 Rico screens, denoised and retyped | 24 + ROOT: BUTTON, PICTOGRAM, TEXT_INPUT, CHECK_BOX, SWITCH, RADIO_BUTTON, LIST_ITEM, CARD_VIEW, SLIDER, SPINNER, TEXT, LABEL, IMAGE, MAP, NAVIGATION_BAR, TOOLBAR, … | **[verify]** | **Best schema match** to the plan's type list; clean labels |
| **VINS** (CHI 2021) | ~4.5k Android/iOS screenshots (+ wireframes) | 11–18 classes, 12 used in YOLO studies | CC BY 4.0 per Roboflow mirror **[verify]** | Small, clean benchmark; YOLOv8s mAP@0.5 = 93.6 |
| **MUD** (CHI 2024) | 18k human-annotated **modern** Android UIs from 3.3k apps | 9 (Button, Checkbox, EditText, Image, Radiobutton, Spinner, Switch, Textview, Togglebutton) | **[verify]** | Modern-style fine-tune data |
| **MobileViews** (2024–25) | 600k → 1.2M screenshot + view-hierarchy pairs from 20k–30k Play apps | Raw VH (needs CLAY-style cleaning) | **MIT** (HF card) | **Largest permissive mobile source**; weak labels from VH |
| **AMEX** (2024) | 104k screenshots from 110 apps; interactive-element boxes + functions | Interactive / not | Gated; license **[verify]** | Good for "interactable" recall |
| **AndroidControl** (Google, 2024) | 15k+ episodes, 833 apps; screenshots + a11y trees + actions | — | **[verify]** | Weak labels; "tapped element" positives |
| **WebUI** (CHI 2023) | 400k web pages | Web semantics | **[verify]** | Pretraining. Web pretraining lifted mobile detection mAP from 0.77 to 0.81 **[verify]**. |
| **ScreenParse v2** (2026) | 1.45M web screenshots, 25.6M boxes, 55 classes | Dense | **[verify]** | Large-scale pretraining |
| **Your own phone** | Screens from the Top-N apps you actually use, with the a11y tree, captured by the actuator | VOS schema | Yours | **The most valuable data by far** (§5) |

---

## 3. On-device feasibility on Tensor G2/G3 (Q2)

### 3.1 Accelerators and runtimes

| Path | G2/G3 status | Verdict |
|---|---|---|
| **LiteRT GPU delegate (OpenCL; Mali-G710 on G2, Mali-G715 on G3 [verify])** | Works on Pixel 8a (Android 16) in two community benchmarks below | **Primary.** Ship FP16. |
| LiteRT CPU (XNNPACK), INT8 | Always available | **Fallback.** Note that XNNPACK refused some FP16 graphs on Pixel 8a, so ship a separate INT8 or FP32 CPU artifact. |
| LiteRT NPU (Tensor SDK / `CompiledModel` NPU) | Official support is **Tensor G5 and G6 only**. Beta with sign-up; runtime recommended via Play Feature Delivery. | **Not for G2/G3.** Revisit only if a G5+ device joins. |
| NNAPI → Edge TPU | Deprecated in Android 15. GrapheneOS staff say third-party apps can still reach TPU/GXP "via standard APIs including NNAPI". Driver coverage of detector ops is unknown. | Optional experiment only. Don't build on it. |
| ONNX Runtime Mobile | CPU (XNNPACK); NNAPI EP is on the deprecated path; no useful Mali GPU EP | Keep for OCR and sherpa-onnx (already in plan S-15/S-23). Not for the detector. |
| "TFLite in Google Play services" | Needs Play | **Not usable.** Bundle LiteRT from Maven inside the actuator APK. |

### 3.2 Measured numbers (Pixel 8a / Tensor G3, from model cards)

| Model | Input | Precision | Runtime | Latency | License |
|---|---|---|---|---|---|
| YOLOX-Nano (2.2 MB) | 416×416 | FP16 | TFLite GPU (OpenCL), 482/482 ops on GPU | **24.0 ms** mean | Apache-2.0 |
| RF-DETR-Nano (split into 2 GPU graphs, 48.6 + 7.6 MB) | 384×384 | FP16 | LiteRT `CompiledModel` GPU | **~27 ms model, ~100 ms end to end** including host pre/post | Apache-2.0 |
| RF-DETR-Nano | 384×384 | FP16 | CPU XNNPACK, 4 threads | 375 + 80 ms | Apache-2.0 |

Lessons from these two cards:

- **CNN YOLOs delegate cleanly to Mali.** DETRs need surgery: `grid_sample` lowers to `GATHER_ND`, there are 5D/6D tensors, and TOPK has no GPU op, which forced a split graph with a host step.
- **Pre- and post-processing can cost three times the model.** On RF-DETR, 27 ms of model became about 100 ms end to end. Feed the GPU from the screenshot's `HardwareBuffer` where possible (LiteRT AHWB buffers **[verify]**), resize on the GPU, and keep NMS and decode in native code.

### 3.3 Estimates for a UI-shaped input [est]

Phone screens are 1080×2400. Material's 48 dp minimum touch target is about 126 px at the Pixel's ~2.6× density. A **448×960** input (≈0.42× scale) keeps every touch target at roughly 50 px or more, which is easy for a detector. Text needs full-resolution crops, not a bigger detector input.

| Detector class | Est. GFLOPs at 448×960 | Est. G3 GPU FP16 | Est. G2 GPU FP16 | Fits ≤120 ms p50? |
|---|---|---|---|---|
| nano / tiny (YOLOX-Nano/Tiny, YOLOv9-T, YOLO11n/26n) | ~2–10 | ~30–60 ms | ~40–80 ms | Yes |
| small (YOLOX-S, YOLOv9-S, YOLO11s/26s, RF-DETR-S) | ~25–35 | ~70–130 ms | ~90–170 ms | Borderline; p95 at risk |
| medium+ (OmniParser V2 ~20M params, YOLO11-L) | 60–200 | 150 ms–1 s | more | No |

These are rough ranges scaled from the 416²/384² measurements. **Measure them on panther** (proposed S-35, §10). Also measure **with Maps navigation rendering**, which competes for the same GPU, and **in a 10-minute loop**, since a dashboard mount in sun will throttle.

### 3.4 Sizes and licenses

| Option | Typical size | License | Commercial-friendly? |
|---|---|---|---|
| Ultralytics YOLOv8/11/26 (incl. Caleb's model, if it is Ultralytics) | 3–25 MB FP16 for n/s | AGPL-3.0; Ultralytics says it covers trained weights; Enterprise license otherwise | Only if VOS is AGPL-compatible open source, or never distributed |
| YOLOX (Megvii) | 2–18 MB | Apache-2.0 | Yes |
| YOLOv9 via MultimediaTechLab/YOLO (+ LibreYOLO) | YOLOv9-T/S small | MIT | Yes (check each weight file's origin) |
| RF-DETR Nano/Small/Medium | ~56 MB (split FP16 Nano) | Apache-2.0 (XL/2XL are PML) | Yes |
| D-FINE / DEIM | ~4M-param nano | Apache-2.0 (DEIMv2 **[verify]**) | Yes; same DETR conversion pain as RF-DETR |
| OmniParser `icon_detect` (V2) / `icon_detect_v3` | ~20M / large | AGPL / MIT | v3 yes, but too big; use as teacher |
| Text recognizer (PP-OCRv5/v6 mobile rec) | a few MB | Apache-2.0 | Yes; shares the S-23 stack |

---

## 4. Dense targets: Spotify lists where every title is clickable (Q3)

**Why it is hard.** Detection is easy here: rows are large, regular, and high-contrast. The failure mode is **choosing**. Grounding studies show the dominant errors are look-alike candidates and icons. Icon errors were 76.9% of failures in one ScreenSpot-V2 analysis. One model scoring 85% on ScreenSpot-V2 drops to 35% when the instruction needs spatial reasoning instead of a name. Planners also emit vague target descriptions more often than grounders mis-click (UGround).

**How the pipeline should handle it:**

1. **Detect structure, not just boxes.** Use `list_row` / `tile` as container classes, with nested `icon` / `button` / `text` children. Class-aware NMS, or an NMS-free head, keeps nested boxes. A row's tap point is its **primary text or row center**, never a trailing overflow `⋮` or "+" icon. Those are separate children, and tapping them is a different action.
2. **Label every candidate cheaply.** Use a11y text first (RecyclerView and LazyColumn rows usually expose title and subtitle). Otherwise run the **recognizer only** on the row's text-line crops. A detector with a `text` class gives these boxes for free, so no OCR-detection pass is needed.
3. **Rank on-device when the goal names the target** ("play *Latent Space*"). Use fused score = detector conf × type match × label similarity. **Act only if top-1 ≥ τ_tap and top-1 − top-2 ≥ δ** (start at δ ≈ 0.15 and tune on M3-BENCH). Otherwise treat the step as ambiguous.
4. **Scroll before escalating** when the target is plausibly off-screen. Record rows already seen in `notes.scratchpad`. Stop after 1–2 scrolls with no match.
5. **Escalate to cloud `vision_step` with Set-of-Mark.** Send the redacted screenshot plus numbered marks drawn from the on-device proposals. The cloud model **returns a mark id**, not pixel coordinates, and the Operator taps that mark's center. This plays to each side's strength: the cloud's semantics, the phone's localization. SoM helps GPT-4o-class models on dense mobile UIs (AMEX, AitZ). If the cloud says "none of the marks," log the frame as a **detector recall miss** and a training sample.

**Escalate when any of these holds** (in addition to the existing §3.5.1 rules):

- The goal is semantic or subjective ("a *new* AI podcast", "the one with the best reviews", "the right entrance").
- There is no candidate with label similarity ≥ θ, or the margin is below δ.
- The step oracle failed once on-device for this target (the plan says two; for dense lists I'd use one).
- The target type is something the detector is weak on (a `link` inside a WebView, a canvas map pin).

**For M3-SPOT specifically:** the path is Search tab (bottom nav, a11y label "Search") → field → `ui.type` the query → results list. The first three steps should be on-device. Choosing the result is a cloud SoM step unless the owner named the show. Follow/Play is on-device again (button + label match).

---

## 5. Fine-tuning YOLO for UI controls (Q4)

### 5.1 Label schema (maps to the plan's type list)

Keep it coarse. Rare classes starve on data. Put fine distinctions in an attribute that a11y or a classifier fills, not in the detector head.

| VOS `type` | Detector class | Includes | CLAY source labels | Notes |
|---|---|---|---|---|
| `button` | `button` | Text buttons, FAB with text, chips, dropdown/spinner triggers | BUTTON, SPINNER | |
| `icon` | `icon` | Icon-only buttons, FAB, toolbar actions, overflow ⋮ | PICTOGRAM (when interactive) | Highest value; the class a11y most often fails to label |
| `field` | `field` | Text inputs, search bars | TEXT_INPUT | |
| `toggle` | `toggle` | Switch, checkbox, radio | SWITCH, CHECK_BOX, RADIO_BUTTON | Sub-kind and checked state come from a11y `isCheckable`/`isChecked` or a crop classifier |
| `tab` | `tab` | Bottom-nav items, top tabs, segmented controls | NAVIGATION_BAR children | Very frequent in Spotify, Maps, Signal |
| `list_row` | `list_row` | Tappable rows / list items | LIST_ITEM | Container for the dense-list case |
| `tile` | `tile` | Cards and cover-art tiles in grids or carousels | CARD_VIEW | Spotify home is mostly tiles |
| `slider` | `slider` | Seek bars, volume sliders | SLIDER | |
| `link` | `link` | Inline text links | (few in native apps) | Mostly WebView/Vanadium; expect low support; lean on a11y |
| — | `text` | Non-interactive text lines | TEXT, LABEL | Drives the crop-only recognizer and suppresses false taps |
| `other` | — | Anything a11y says is clickable but the detector has no class for | — | Comes from a11y, not the head |

That gives 10 detector classes. Containers like toolbar, drawer, and navigation bar are dropped as classes and kept only as context.

### 5.2 Data, in order of value

1. **Your own Top-N apps (the core).** A debug actuator build captures `(screenshot, a11y tree, package, versionCode, timestamp)` on every M3 run and during an owner-driven "capture session." Export goes over **adb/USB to UStor2**: the actuator has no network, and this keeps it that way.
   - **Auto-label:**
     - a11y nodes with `isClickable` / `isEditable` / `isCheckable` / `isVisibleToUser`, where `className` → type. Compose maps `Role` to classic widget class names, so this works for most Compose apps **[verify per app]**.
     - Plus an **OmniParser `icon_detect_v3` teacher** (MIT) run on the desktop, plus OCR for text lines.
     - Merge with IoU rules; flag disagreements.
   - **Human-correct only the disagreements** in CVAT or Label Studio, locally. That is where the hard cases are: unlabeled icons, canvas, and giant merged clickables.
   - **Successful-trajectory positives:** the element tapped right before a passing oracle is a verified positive, which is free supervision from M3 itself.
   - **Privacy:** these frames hold Signal chats and notifications. Keep the corpus encrypted at rest on UStor2. Exclude or blur messaging content (the text recognizer can locate text to blur). Never use a cloud labeling service. Skip `FLAG_SECURE` frames.
2. **Public mobile data for breadth:**
   - CLAY (clean labels, best schema match).
   - MUD (modern Android).
   - MobileViews (MIT, huge; weak VH labels need CLAY-style filtering for occluded or invisible nodes).
   - VINS (small, clean).
   - AMEX (interactive boxes; check the license).
3. **Web data for pretraining only:** ScreenParse v2 or WebUI. Pretraining on web then fine-tuning on mobile helped in WebUI's study **[verify]**.

**Volume targets [est]:**

- Pretrain: CLAY + MUD + a filtered MobileViews subset (~50–100k screens).
- Fine-tune: 2–4k own-app frames (≈150–300 per Top-N app), with ~500 human-verified.
- Grow it continuously from M3 runs.

### 5.3 Training recipe

- **Input:** 448×960 (or 384×832 as a "fast" variant); letterbox, keep portrait. Train at the deploy resolution.
- **Augmentations that match real phones:**
  - Dark mode and Material You **hue shifts** (wallpaper-derived colors change app palettes).
  - Font-scale and display-size changes (scale jitter 0.8–1.3).
  - Locales, including RTL.
  - Status-bar and nav-bar variants; IME up/down.
  - Notification heads-up overlays; partial scroll offsets (cropped rows at the top and bottom edges).
- **Avoid or limit:** heavy mosaic (it breaks screen layout priors) and horizontal flip, which changes meaning (back vs. forward). Use it at ≤0.1 or not at all.
- **Splits by app, not by frame**, with a held-out **unseen-apps** split to measure generalization. Frame-level splits leak and inflate mAP badly on UI data.
- **Export:** PyTorch → ONNX → LiteRT FP16 (GPU) + INT8 (CPU, calibrated on own-app frames). Verify 100% of ops on GPU with `benchmark_model`.
- **Confidence calibration:** fit isotonic or logistic calibration on the fused score (detector conf, a11y match, label margin) so τ_tap means a real precision (§7).

### 5.4 Expected accuracy

| Setting | Published reference | Expectation for VOS [est] |
|---|---|---|
| Clean, in-domain, frame-split | VINS, 12 classes: YOLOv8s mAP@0.5 **93.6**, mAP@.5:.95 85.9 | Treat as an upper bound; not representative |
| Large real corpus, many classes | Apple Screen Recognition: **71.3% mAP** over 13 classes on 77k iOS screens (82.7% frequency-weighted) **[verify]**. MUD: Faster R-CNN 82.2% at IoU 0.5 vs 71.7% on Rico. | |
| Off-domain, web → mobile | OmniParser V2 ScreenSpot-mobile target recall **0.489**; RT-DETRv2 trained on ScreenParse 0.584 | Shows that **mobile-specific fine-tuning is required** |
| **VOS after own-app fine-tune** | — | Class-agnostic interactive recall at IoU 0.5 ≈ **85–95%** on seen apps and ≈75–85% on unseen apps. Type accuracy ≈85–90% for button/icon/field/tab/list_row, worse for link/slider. With a11y fusion, target-hit recall on seen apps ≈95%+. |

### 5.5 What it would take

| Step | Effort [est] |
|---|---|
| Capture tool in the debug actuator + adb export + local labeling setup | 2–3 days |
| Auto-label merge (a11y + teacher + OCR) + disagreement queue | 2–3 days |
| First 2k own-app frames + 500 human checks | 2–4 days of owner time, spread out |
| Pretrain + fine-tune + export + on-device bench (panther, akita) | 3–5 days, given an existing YOLO pipeline |
| Fusion + ranking + calibration in the actuator | 3–5 days |

That is roughly **2–3 weeks to a measured v0**, and it fits inside M3 (target 2027-01-15).

---

## 6. Should a11y node bounds be merged? (Q5)

**Yes, a11y should be the first signal, with the detector filling its gaps.** The actuator already holds the a11y service, so the extra cost is one tree snapshot, which is already inside the plan's ≤80 ms capture budget.

**Pros:**

- Exact bounds, **labels** (`text` / `contentDescription`), **roles** (`className`, Compose `Role`), and **state** (checked, enabled, selected, focused).
- **Actions** (`ACTION_CLICK`, `ACTION_SET_TEXT`, `ACTION_SCROLL_FORWARD`) that work without coordinates and are robust to animations.
- Zero model cost. Lists expose off-screen-but-laid-out items.
- Still available when the screenshot is blank because of `FLAG_SECURE` (the plan's "tree-only" path).
- Solves the dense-list labeling problem for free when rows have text.

**Cons and failure modes:**

| Case | What happens |
|---|---|
| **Missing labels** | Very common: >77% of 10,408 apps had unlabeled image buttons (LabelDroid). In another study, ~93% of FABs lacked a content description. The node exists but says nothing, so the detector + icon classifier or the cloud must name it. |
| **Canvas / GL surfaces** | Map views, video, games, custom-drawn charts: one opaque node. Map pins and roads in Maps are mostly invisible to a11y, which is exactly the live-map look-around case. Detector + cloud only. |
| **Compose** | Usually good, because Material components set roles and merge semantics. But `Modifier.clickable` on a plain `Box` gives a clickable node with no role or label. `clickable` on a parent **merges all descendants** into one giant node, so the tap point is the container center. Gestures built from raw `pointerInput` without semantics are invisible. |
| **Flutter / React Native** | Flutter exposes semantics as virtual views through its `AccessibilityBridge`; quality depends on the app. Some Flutter builds only build the semantics tree once an a11y service is active **[verify]**. Since VOS's service is active, this probably helps. |
| **WebView** | Exposed through Chromium's a11y tree, often well, sometimes lazily populated. |
| **Stale or occluded nodes** | Nodes under dialogs, sheets, or other layers still appear. AMEX had to filter these by hand. Use window layering plus `isVisibleToUser` plus a detector cross-check. |
| **Cost on huge trees** | Cross-process traversal can be slow on complex screens (no published numbers found). Measure it (S-36), prune to visible windows, and use prefetch strategies. |
| **Adversarial labels** | `contentDescription` is app-controlled text. Treat it as quoted data with an origin tag (§5.6 of the plan); never as instructions. |

**Merge rule (v0):**

1. Pull visible, on-screen nodes that are clickable, editable, checkable, or scrollable. Map `className` / role → VOS type.
2. Run the detector. Match boxes to nodes by IoU ≥ 0.5, or by the detector's center falling inside the node's bounds when the node is a merged container.
3. **Matched:** use the a11y label, type, and actions; boost confidence.
4. **A11y-only (detector missed):** keep it with an a11y-prior confidence, and log it as a detector recall miss for training.
5. **Detector-only (no node):** keep it with detector confidence. Label it via the crop recognizer, or an icon classifier later. These are the canvas, unlabeled, and custom-drawn cases.
6. **Giant clickable container with detector children inside:** emit the children as targets and keep the container as `list_row` / `tile`.

**Proposed optional fields on the fixed contract:** `label_src ∈ {a11y, ocr, none}`, `sources ⊆ {det, a11y}`, `a11y_node_ref?`, `actions?`. With these, the Operator can prefer `ACTION_CLICK` on a matched node over a coordinate tap.

DailyDroid (Apr 2026, 75 tasks across 25 Android apps) shows why both are needed. Tree-only agents already did most of the work: GPT-4o went from 26.7% text-only to 32.0% with screenshots, at ~25× the tokens. Screenshots mattered exactly where the tree lacked visual cues (icons, progress, missing components). VOS gets the same complementarity with an on-device detector instead of paying for cloud tokens on every step.

---

## 7. M3-BENCH design: live current apps, no version pins (Q6)

**Principle:** the gate is **task success on current apps**. Detection metrics are diagnostics that explain why tasks fail and catch drift early. mAP alone is a poor proxy:

- It rewards box tightness that doesn't matter for a tap.
- It averages over classes and elements the task never touches.
- It ignores calibration at the operating threshold.

MMBench-GUI found localization improvements alone raised success 2.8× with the planner held fixed. Grounding matters; it just has to be measured at the target, at τ.

### 7.1 Three layers

| Layer | Data | Primary metrics | Secondary |
|---|---|---|---|
| **L0 Perception** (offline, per frame) | Rolling **golden frames** from live M3 runs and capture sessions. Labels come from a11y + teacher + OCR auto-labels, with a **human-verified monthly sample** (~300 frames). Each frame carries `package`, `versionName`/`versionCode`, and date. | **Interactive recall** (IoU ≥ 0.5 or center-in-box). **Tap-hit rate:** the proposal's tap point lands inside the true element and no other. **Precision at τ_tap.** **Type accuracy** with a confusion matrix. **Label accuracy** (normalized edit distance). | mAP@0.5, mAP@.5:.95; calibration error (ECE); latency p50/p95 on panther and akita |
| **L1 Step grounding** | (frame, step goal) pairs from real trajectories, e.g. "tap Search", "focus the search field", "open the 2nd result titled X" | **On-device resolve rate** (conf ≥ τ and correct). **Wrong-tap rate at τ** (the safety metric). Escalation rate. Ambiguity-detection rate on dense frames. | Cloud SoM mark-pick accuracy; "none of the marks" rate (= detector misses) |
| **L2 Task** | `eval/tasks.yaml` Top-N on current apps (M3-UI-01, M3-SPOT, M3-MAP) | **Oracle success** (≥70% cold). **≥60% of successful cold steps on-device** (M3-UI-05). Time-to-effect. Wrong taps per task. | Cloud bytes and cost per task; recoveries; route-replay hit rate |

### 7.2 Rules that keep it honest without version pins

- **No pinned APKs as a gate** (plan rule). Every row logs `package` + version so drift is visible.
- **Re-run on update:** the harness watches app updates (Play / Graphene / Accrescent / Aurora) and re-runs that app's tasks within 24 h. A recall or success drop of more than ~5 points opens a drift note and queues those frames for labeling and retraining.
- **Content is stochastic** (Spotify recommendations and search results change daily). Run **N ≥ 3 trials per task** and report Wilson 95% confidence intervals. Compare detectors **paired on the same frames** (L0/L1) and **interleaved** in time (L2).
- **Split by app, not by frame.** Report seen-app and **unseen-app** numbers separately. Unseen apps are the real generalization test.
- **Freshness window:** score L0 on the last 30 days of frames, and keep older frames as a regression set that is reported separately.
- **Thermal / contention runs:** a 10-minute continuous loop on panther with Maps navigation running and the GPS spoof fixture (M3-MAP-03) gives p95 under realistic GPU load.
- **External sanity checks (not gates):** the ScreenSpot-V2 mobile split for grounding, and AndroidWorld-style state-checked tasks for harness ideas. Both use fixed environments, the opposite of the M3 rule, so they inform but never gate.

### 7.3 Suggested starting thresholds [est; tune on data]

- **v0 gate on L0 (seen apps):** interactive recall ≥ 90% and precision at τ_tap ≥ 97%.
- **L1:** wrong-tap rate at τ ≤ 1% of on-device taps.
- **L2:** the plan's existing gates.

---

## 8. Ranked shortlist and v0 recommendation

### 1. v0 pick: A11y ∪ small YOLO-family UI detector + crop-only recognizer

- **Why:**
  - Fits the budget with margin on G2/G3 GPU (nano/tiny class).
  - Caleb already has a YOLO training pipeline.
  - Gives full control of the label schema to match the plan's types.
  - a11y fusion supplies labels and actions for free.
  - Improves itself from M3 trajectories.
- **Model size:** start at nano/tiny at 448×960. Move to small only if L0 recall on unseen apps is <85% and p95 still fits.
- **Framework:** Caleb's existing YOLO if the AGPL question is settled (§9 Q2). Otherwise YOLOX-Tiny/S (Apache-2.0) or YOLOv9-T/S via MultimediaTechLab/LibreYOLO (MIT). Keep the data, labels, export, and bench pipeline **architecture-agnostic** so the head can be swapped.
- **ModelRouter policy (proposal):**

  ```yaml
  control_detect:            # on_device_actuator only
    - id: vos-uidet-v0-fp16-gpu      # fused with a11y + crop recognizer
    - id: vos-uidet-v0-int8-cpu      # same weights, CPU fallback
    - id: a11y-ocr-heuristic         # no detector
  ```

### 2. RF-DETR-Nano/Small fine-tuned (Apache-2.0)

- **Why second:**
  - NMS-free, so nested and dense boxes behave better.
  - In the ScreenParse study, the RT-DETR family had the best mobile recall among the detectors tested.
  - Already shown running fully on the Pixel 8a GPU (~27 ms model).
- **Why not first:**
  - Conversion needed re-authoring (split graph, `grid_sample` rewrite, fp16-safe LayerNorm).
  - ~100 ms end to end in that port.
  - Larger artifacts (~56 MB FP16).
  - Fragile across LiteRT versions and harder to maintain alone.

### 3. OmniParser-style detector

- **Use it as:**
  - The day-1 **zero-training baseline** on the M3-BENCH frames: V2 `icon_detect` exported at reduced input. AGPL is fine for local benchmarking.
  - The **desktop teacher** for auto-labeling: `icon_detect_v3`, MIT.
- **Don't ship as `control_detect`:** web-tuned, class-agnostic (no `type`), and v3 is too large. Its captioner is the slow part anyway.

### Fallback: A11y + OCR heuristics, then cloud

- Works on day 1, and it is already the plan's "labeled a11y node with conf ≥ τ" path.
- Honest gaps: unlabeled icons, canvas, giant merged clickables. Those escalate to cloud `vision_step` more often, so M3-UI-05's 60% is at risk.

### Not in this slot: a small local grounding VLM

- Candidates: Holo-3.1-0.8B, Qwen3-VL-2B, MAI-UI-2B (Apache-2.0).
- Register it later as an **offline `vision_step` backend in the session** (`on_device_session`, seconds, spoken estimate first). It is for offline or spend-cap cases.
- Needs its own spike for size (≈0.5–1.5 GB quantized) and latency on panther.

---

## 9. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **App redesigns / drift** (no pins by design) | Recall drops after an update | a11y fusion as a floor; update-triggered re-runs; frames from failed steps feed retraining; per-app drift charts |
| **AGPL** (Ultralytics weights, OmniParser V2 detector) | Blocks closed distribution of VOS | Decide the VOS license (Q2); keep the pipeline architecture-agnostic; Apache/MIT heads ready |
| **GPU delegate on GrapheneOS** (vendor OpenCL access, hardened_malloc/MTE, driver bugs) | Detector falls to CPU (375 ms-class for DETR; slower for bigger YOLOs) | Fold into S-24; INT8 CPU artifact as a fallback that is still usable; nano-class model so even CPU stays near budget |
| **Pre/post-processing overhead** | Model 25 ms, pipeline 100 ms | Zero-copy `HardwareBuffer` input, GPU resize, native NMS; budget the whole pipeline, not the model |
| **GPU contention and thermals** (Maps nav, sun-loaded mount) | p95 blows past 250 ms | Measure under load; adaptive input (fast 384×832 variant); skip detection when a11y already resolves the step |
| **Overconfident wrong taps** | Wrong podcast opened, wasted steps; reversible but erodes trust | Calibrated fused score; margin rule δ; wrong-tap rate as an L1 gate; irreversible acts still go through Announcer |
| **Dense or semantic choices** | On-device picks the wrong row | SoM escalation by rule (§4); cloud returns a mark id |
| **Privacy of the training corpus** | Personal messages in training frames | Local-only labeling; encrypted store; blur messaging text; exclude `FLAG_SECURE`; no cloud labelers |
| **Dataset license ambiguity** (CLAY, MUD, AMEX, AndroidControl unverified) | Can't ship weights trained on them | Verify before training a model meant for distribution; own-app data plus MIT MobileViews is the clean core |
| **Pixel 7 end of support (~Oct 2027)** | panther-only tuning ages out | Bench both panther and akita from v0 |
| **Temptation to put a VLM in the hot path** | Seconds per step; battery | Keep the VLM in `vision_step`; `control_detect` stays a detector |

---

## 10. Proposed plan deltas (not applied; for Caleb to accept or reject)

- **S-35 Detector on GPU under Graphene:**
  - A FP16 detector at 448×960 on LiteRT GPU on panther and akita.
  - p50/p95 for the whole pipeline (capture → tensor → model → decode).
  - A 10-minute loop with Maps nav + GPS spoof; INT8 CPU fallback numbers; 0 crashes under hardened_malloc/MTE.
  - **Pass:** p50 ≤120 ms and p95 ≤250 ms on panther.
- **S-36 A11y coverage and latency on Top-N apps:**
  - Fraction of visually interactive elements that have a visible clickable node.
  - Fraction of those with a label.
  - Snapshot p50/p95.
  - Decides how much the detector must carry per app.
- **S-37 Capture and auto-label loop:** a debug actuator capture → adb export → local teacher + a11y + OCR merge → disagreement queue. No network on the actuator.
- **§3.2.1:** replace the `control_detect` placeholder with the policy rows in §8 after S-35.
- **§3.5.1:** add the margin rule (δ) and "SoM mark id, not xy" to the escalation description; add the optional proposal fields from §6.
- **M3-BENCH-01:** add the L0/L1/L2 layers and metrics from §7.

---

## 11. Open questions for Caleb

1. **Your fine-tuned YOLO:**
   - Which framework and version (Ultralytics v8/11/26? another)?
   - What input size, classes, and training data, and how were labels made?
   - What task was it "similar" to, and what precision/recall did it reach?
2. **VOS license and distribution:** will VOS ever ship beyond your own devices (Accrescent, F-Droid, or sideload to others)? If yes, is AGPL-compatible open source acceptable? This decides Ultralytics vs. Apache/MIT heads.
3. **Corpus capture from the daily phone:** OK to capture screens plus a11y trees from Phone A during normal use? Which apps are excluded (Signal, banking)? Is blur-text-in-messaging enough?
4. **Training hardware:** which desktop GPU is available for pretraining (~50–100k screens) and fine-tuning?
5. **Label schema:** accept the 10 classes in §5.1 (`button, icon, field, toggle, tab, list_row, tile, slider, link, text`)? Should `tab` and `tile` be first-class VOS `type`s in the contract, or fold into `button`?
6. **Wrong-tap tolerance:** is ≤1% of on-device taps the right bar for reversible steps? Should it be stricter in some apps (payments, messaging), beyond the trusted path?
7. **Offline local VLM:** worth a spike for an offline `vision_step` in the session (0.8–2B, ~0.5–1.5 GB, seconds per step), or keep `vision_step` cloud-only for M3?
8. **Top-N app list for M3-BENCH:** confirm from S-25 (Spotify, Maps / Organic Maps, Signal, AntennaPod, Vanadium, Settings, …).

---

## Sources

**Detectors and screen parsers**
- OmniParser repo (news, licenses incl. `icon_detect_v3` MIT): https://github.com/microsoft/OmniParser
- OmniParser V2 model card (AGPL detector, MIT caption, 0.6 s/A100): https://huggingface.co/microsoft/OmniParser-v2.0
- OmniParser YOLOv9-E PR #37: https://huggingface.co/microsoft/OmniParser-v2.0/discussions/37
- OmniParser V2 article: https://www.microsoft.com/en-us/research/articles/omniparser-v2-turning-any-llm-into-a-computer-use-agent/
- ScreenParse (ICML 2026): https://saidgurbuz.github.io/screenparse/
- docling ScreenParser (YOLO11-L, 55 classes): https://huggingface.co/docling-project/ScreenParser
- Salesforce GPA-GUI-Detector: https://huggingface.co/Salesforce/GPA-GUI-Detector/blob/main/README.md
- racineai CU-1 (RF-DETR-M UI detector): https://huggingface.co/racineai/CU-1/blob/main/README.md
- TargetFinder (desktop widgets): https://arxiv.org/pdf/2607.19907
- Apple Screen Recognition (CHI 2021): https://arxiv.org/pdf/2101.04893
- UIED: https://github.com/MulongXie/UIED, https://research.monash.edu/en/publications/uied-a-hybrid-tool-for-gui-element-detection/
- GUI element detection with YOLO on VINS (YOLOv8s 93.6 mAP@0.5): https://arxiv.org/html/2408.03507v1

**Grounding VLMs**
- Ferret-UI Lite: https://huggingface.co/papers/2509.26539
- MAI-UI technical report (Qwen3-VL-2B SS-V2 86.7): https://arxiv.org/pdf/2512.22047
- Holo-3.1-0.8B model card: https://huggingface.co/Hcompany/Holo-3.1-0.8B
- AndroidWorld leaderboard snapshot (Holo3.1-4B 71.0%): https://benchlm.ai/benchmarks/androidworld
- Florence-2-base: https://huggingface.co/microsoft/Florence-2-base; latency notes: https://docs.curiosity.ai/florence2-sharp/advanced/performance
- Gemma 3n: https://huggingface.co/blog/gemma3n
- Qualcomm AI Hub Qwen3-VL-2B: https://aihub.qualcomm.com/models/qwen3_vl_2b_instruct

**Datasets**
- CLAY: https://github.com/google-research-datasets/clay (label map: `label_map.txt`)
- VINS: https://arxiv.org/pdf/2102.05216
- MUD: https://arxiv.org/abs/2405.07090
- MobileViews: https://huggingface.co/datasets/mllmTeam/MobileViews, https://arxiv.org/abs/2409.14337v3
- AMEX: https://arxiv.org/pdf/2407.17490
- AndroidControl: https://arxiv.org/pdf/2406.03679
- WebUI: https://arxiv.org/pdf/2301.13280

**On-device runtime (Tensor, LiteRT, NNAPI, GrapheneOS)**
- LiteRT NPU: https://developers.google.com/edge/litert/next/npu
- Google Tensor SDK (G5/G6 only, beta): https://developers.google.com/edge/litert/next/tensor-sdk
- NNAPI migration guide (deprecated in Android 15): https://developer.android.com/ndk/guides/neuralnetworks/migration-guide
- GrapheneOS release note on Tensor TPU/GXP access: https://discuss.grapheneos.org/d/4437-grapheneos-version-2023041100-released
- YOLOX-Nano LiteRT (Pixel 8a 24.0 ms GPU): https://huggingface.co/litert-community/yolox-nano-litert
- RF-DETR-Nano LiteRT (Pixel 8a ~27 ms model / ~100 ms e2e; GPU surgery): https://huggingface.co/litert-community/RF-DETR-Nano-LiteRT
- RF-DETR export docs: https://rfdetr.roboflow.com/latest/learn/export/
- DEIM / D-FINE: https://github.com/Intellindust-AI-Lab/DEIM; DEIMv2: https://arxiv.org/pdf/2509.20787v3
- PP-OCRv6 paper (PP-OCRv5 mobile timings): https://arxiv.org/pdf/2606.13108; PaddleOCR Android: https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html

**Licenses**
- Ultralytics licensing (AGPL covers trained weights per Ultralytics): https://ultralytics.com/license, https://community.ultralytics.com/t/yolov11-agpl-license-for-on-premises-use/1529
- MIT YOLOv9 implementation: https://gittrend.io/repo/MultimediaTechLab/YOLO
- YOLOX (Apache-2.0): https://playground.roboflow.com/models/megvii/yolox
- YOLO26: https://www.ultralytics.com/yolo/yolo26

**Accessibility, grounding failures, evaluation**
- LabelDroid (>77% of apps miss image-button labels): https://arxiv.org/pdf/2003.00380
- UW CREATE large-scale a11y analysis: https://create.uw.edu/initiatives/large-scale-analysis-finds-many-mobile-apps-are-inaccessible
- Compose semantics merging: https://developer.android.com/develop/ui/compose/accessibility/merging-clearing
- Flutter AccessibilityBridge: https://api.flutter.dev/javadoc/io/flutter/view/AccessibilityBridge.html
- DailyDroid (screentext vs screenshots on Android): https://arxiv.org/html/2604.17817v1
- GUI agents survey (hybrid perception): https://arxiv.org/pdf/2411.18279
- GUI-G² (icon errors 76.9% of failures): https://arxiv.org/pdf/2507.15846
- RankGround (reranker-guided crop selection): https://arxiv.org/abs/2609.18690
- UGround: https://arxiv.org/pdf/2410.05243
- GUI-Perturbed: https://arxiv.org/pdf/2604.14262
- MMBench-GUI (grounding drives success 2.8×): https://arxiv.org/pdf/2507.19478
- SeeClick: https://arxiv.org/html/2401.10935v2
- Set-of-Mark on mobile (AMEX, AitZ): https://arxiv.org/pdf/2407.17490, https://arxiv.org/pdf/2403.02713
- UI-TARS: https://arxiv.org/pdf/2501.12326
