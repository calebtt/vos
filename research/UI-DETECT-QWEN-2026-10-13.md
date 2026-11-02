# On-device UI element / clickable detection for VOS — Qwen independent opinion

| Field | Value |
|---|---|
| Script | `/tmp/launch-koboldcpp.sh` |
| Model | `koboldcpp/Huihui-Qwen3.8-27B-abliterated-UD-Q4_K_XL` |
| Endpoint | `http://127.0.0.1:5001/v1/chat/completions` |
| Date | 2026-10-13 |
| max_tokens | 8000 |
| HTTP status | 200 |
| finish_reason | length |
| elapsed_s | 1040.5 |
| usage | `{"prompt_tokens": 1794, "completion_tokens": 8000, "total_tokens": 9794}` |
| Sampler fields | omitted on purpose, so the server gendefaults apply (thinking left on) |

Qwen was not shown the Claude or Grok UI-detect reports, and was not told any prior recommendation. The text below is the model's answer with thinking removed. The first completion hit `finish_reason=length` (8000 tokens, reasoning included). Two continuation calls finish the note and are marked in place. Raw JSON is `research/qwen-raw-response.json`, `research/qwen-raw-response-2.json`, and `research/qwen-raw-response-3.json`. The exact brief is in the appendix.

---

# Research note: on-device `control_detect` for VOS

## 0. Scope and assumptions

- `ui.control_detect` receives an already-captured screenshot plus whatever local OS data the actuator can access.
- The detector must run inside `com.vos.actuator`, no network, no vendor API key.
- It should return element proposals: `bbox`/`center`, `type`, optional `label`, `confidence`.
- It does not decide the user’s goal. It helps the actuator take easy on-device actions and gives the session enough candidates to escalate when needed.
- Target latency on Pixel 7 / Tensor G2: about 120 ms p50, 250 ms p95 for detection itself. Screenshot capture is separate.
- Reference screens are roughly 1080 × 2400 px. Some buttons and map chrome can be only a few dozen pixels wide.
- We modify and sign the OS image, so accessibility, framework hooks, and window-manager changes are allowed. Play services are not a dependency.

My broad opinion: the right v0 is probably **not** a standalone neural detector. The strongest first design is likely an **accessibility-first pipeline**, augmented with **OCR labels** and **classic row/shape heuristics**. A small quantized object detector is a strong second-stage fallback for canvas UI, icon-only controls, and dense unknown layouts, but it brings data, license, and calibration costs that may not be worth solving before measuring how much standard Android accessibility already covers the live benchmark apps.

The hardest parts of this job are not “find all clickables” in a normal settings screen. They are:

1. Dense same-type lists where every title looks similar, e.g. Spotify podcast rows.
2. Live canvas surfaces such as Google Maps or Organic Maps, where most content is drawn and only a few chrome buttons are exposed.
3. Semantic selection among many geometrically valid candidates, e.g. choosing “a new AI-related podcast” from many podcast rows.

Those semantic choices should often be escalated to a multimodal model. The on-device detector’s job is to make easy frames cheap and fast, and to make escalations cheaper by providing candidate boxes, labels, and confidence.

---

# 1. Solution families

## 1.1 Accessibility / view-hierarchy data

### What it is

Android exposes app UI through `AccessibilityNodeInfo`. Nodes have:

- `boundsInScreen`
- `text`
- `contentDescription`
- `className`
- `viewIdResourceName`
- `stateDescription`
- `isClickable`
- `isEditable`
- `isChecked`
- `isCheckable`
- `isScrollable`
- `roleDescription`, available on newer platforms [uncertain]
- `importantForAccessibility`
- child/parent relationships

A VOS actuator with an accessibility service can collect the foreground window’s tree almost directly.

### Contract mapping

- `bbox`: node bounds.
- `center`: midpoint of node bounds.
- `type`:
  - clickable button-like node → `button`
  - editable node → `field`
  - checkable node → `toggle`
  - icon-like node with `contentDescription` but no text → `icon`
  - text node in a row/list context → `other` or a richer `list_row` type if argued for
  - link-like text → `link` only if role/class evidence exists
- `label`: `text` or `contentDescription`.
- `confidence`:
  - exact clickable node: high, e.g. 0.9–0.98
  - exact editable node: high
  - exact checkable node: high
  - text-only node that may be tappable: medium, e.g. 0.5–0.75
  - ambiguous node: low

### Pros

- Very fast. Node collection is usually much faster than 120 ms.
- High precision for standard widgets.
- Provides labels naturally.
- Works well enough on virtualized lists for visible rows.
- No training.
- License is effectively AOSP / Android platform license, Apache 2.0 family.
- Fits perfectly inside the actuator.

### Cons

- App-dependent.
- Icon-only buttons may lack useful `contentDescription`.
- Many custom views expose only a coarse node.
- Canvas and map surfaces are mostly not represented as UI nodes.
- Some apps put clickability on a parent row; others put it only on a title or image.
- Accessibility trees can be delayed or incomplete for complex animated UI.
- Stale state is possible if the tree is cached rather than reread.

### Latency on Tensor G2 / G3

Should easily meet 120 ms p50. If only collecting the foreground window tree and filtering nodes, I would expect single-digit to tens of milliseconds [uncertain]. The risk is not latency but coverage.

### Behavior on Spotify dense lists

Probably good for geometry. Many list apps expose row items as clickable nodes or expose title text nodes. Even if the whole row is clickable, accessibility may return one row node per visible item. That gives the agent many candidate boxes. The semantic choice of “new AI podcast” still requires text/OCR and probably escalation.

If accessibility returns rows but not per-title clickability, the detector can still emit medium-confidence row proposals with labels from `text` or OCR.

### Behavior on live map

Weak-to-medium. Maps will likely expose a few chrome controls: search, layers, zoom in/out, recenter, navigation button, etc. The map surface itself—roads, pins, labels, turn instructions—will mostly be canvas. The detector should not try to answer map questions from one screenshot. It should propose map controls so the session can pan, zoom, recenter, and then escalate for spatial understanding.

### Verdict

Strong v0 foundation. Not sufficient alone for icon-only/canvas UI, but likely covers a large share of standard UI tasks.

---

## 1.2 OS and framework instrumentation

### What it is

Because VOS builds and signs the image, we can instrument AOSP beyond a normal app. Possible hooks:

- `View`
- `ViewGroup`
- `Window`
- `ViewRootImpl`
- `AccessibilityInteractionController`
- `InputMethodManager`
- `TouchDelegate`
- `OnTouchListener` regions
- custom framework semantic tags
- drawable and paint-operation logging
- input-region extraction from `draw()` calls
- per-window FLAG_SECURE handling at the window manager level

This is not merely an accessibility service. It is a modified Android platform that can see more of what apps draw and how they handle input.

### Contract mapping

- `bbox`: view bounds or inferred touch region.
- `center`: midpoint.
- `type`: derived from view class, custom semantic tag, or inferred from paint/drawables.
- `label`: view text, content description, resource ID, or OCR fallback.
- `confidence`: high for explicit view state, lower for inferred regions.

A richer type set becomes useful here:

- `button`
- `field`
- `toggle`
- `icon`
- `link`
- `list_row`
- `map_control`
- `canvas_hotspot`
- `chrome_button`
- `unknown_tap_target`

### Pros

- Potentially better than public accessibility for standard widgets.
- Can expose internal view IDs and semantic tags.
- Can improve map chrome detection if map controls are ordinary views.
- Can provide better handling of FLAG_SECURE windows because we own the compositor/window path.
- Can create a VOS-specific UI semantic layer over time.
- Can expose clickability hints from framework internals without depending on app authors.

### Cons

- More engineering than accessibility.
- Must track AOSP / GrapheneOS changes.
- Potential performance overhead if instrumentation is too broad.
- Privacy-sensitive: we are looking deeper into app internals.
- May leak app-internal IDs that change across versions.
- Could introduce hidden dependencies if not carefully isolated.
- Instrumenting every `draw()` operation may be expensive.

### Latency on Tensor G2 / G3

If done as a foreground-window tree snapshot, likely still within budget. If it logs all draw operations continuously, could add overhead. The detection step itself can use cached structured state, but freshness must be managed.

### Behavior on Spotify dense lists

Likely similar to accessibility or slightly better. Row view IDs and class names may help group rows. If resource IDs are stable enough, row detection becomes easier. But app updates can change IDs, so no version pinning means the pipeline must fall back gracefully.

### Behavior on live map

Possibly better than plain accessibility if map chrome buttons are views. But the map canvas remains mostly custom-drawn. Framework hooks alone probably cannot understand roads or pins unless apps expose semantic map objects, which is unlikely.

### Verdict

Worth doing in stages. Do not make this the v0 bottleneck. Start with accessibility, then add targeted framework instrumentation for cases where accessibility coverage is poor.

---

## 1.3 Classic vision / geometry methods

### What it is

Non-neural image processing:

- Canny edge detection
- contour extraction
- connected components
- morphological opening/closing
- horizontal/vertical projection profiles
- row banding
- template matching
- Hough line transforms
- corner detection
- color segmentation
- simple aspect-ratio and overlap heuristics

These methods can detect UI structure without trained models.

### Contract mapping

- `bbox`: contour or projection band.
- `center`: midpoint.
- `type`: inferred from shape heuristics:
  - rounded rectangle → `button`
  - text baseline inside box → `field` if underlined/bordered
  - small square/check-like icon → `toggle`
  - repeated horizontal band → `list_row`
  - small isolated icon → `icon`
  - unknown clickable-looking region → `other`
- `label`: from OCR, not from classic vision alone.
- `confidence`: moderate; usually lower than explicit accessibility.

### Pros

- No training.
- Explainable.
- Can work on high-contrast UI.
- Useful for dense lists via row projection.
- Useful for separators and repeated rows.
- Can be fast if run at reduced resolution.
- License-friendly if using OpenCV etc., Apache 2.0 [uncertain].

### Cons

- Brittle to style changes.
- Does not understand clickability.
- Text and icons are easily confused.
- Small buttons can be missed if downscale is too aggressive.
- Dense same-type lists still need OCR/semantic selection.
- Map surfaces are not standard widgets, so shape heuristics only help for chrome buttons.
- False positives: every visible box may not be tappable.

### Latency on Tensor G2 / G3

At 640 px width, classic geometry should often be within 120 ms [uncertain]. Full 1080 × 2400 may be slower depending on contour complexity. A staged approach is better:

1. Downscale to 512 or 640 width.
2. Run projection/row detection.
3. Only process candidate regions at higher resolution if needed.

### Behavior on Spotify dense lists

Likely strong for row geometry. Horizontal projection and separator detection can find repeated podcast rows. Combined with OCR labels, it can output many candidate rows. It probably cannot decide which row is “a new AI podcast.”

### Behavior on live map

Limited. Classic vision can detect obvious zoom plus/minus buttons or recenter icons if templates or shape rules exist. It should not attempt to interpret the map canvas beyond maybe finding large turn-instruction cards if they are UI-like overlays.

### Verdict

Use as a support layer, especially for lists. Do not make it the primary clickability oracle.

---

## 1.4 Text recognition / OCR

### What it is

Scene-text detection and recognition. Relevant families:

- Tesseract [uncertain]
- CRAFT-style text detection [uncertain]
- DBNet / DRRN-style detection [uncertain]
- PaddleOCR: DB/MOV2 detector + CRNN recognizer [uncertain]
- EasyOCR: CRAFT/SRT variants [uncertain]
- SRT / single-line text recognizers [uncertain]
- MobileNetV4-based backbones with CTC/CrNN heads [uncertain]
- Tiny on-device OCR engines using TFLite/NCNN [uncertain]

The practical mobile pattern is:

1. Detect text lines/words.
2. Crop each line/word.
3. Run a small line recognizer.
4. Map recognized text back to nearby UI boxes.

### Contract mapping

- `bbox`: text box.
- `center`: tap point if text is the control.
- `type`: usually `other`, `field_hint`, `button_label`, `list_row`, or `icon_description` depending on context.
- `label`: recognized text.
- `confidence`: OCR confidence, reduced when text box is only loosely associated with a clickable node.

A richer design would have a separate `text_element` proposal type so the agent does not confuse “there is text” with “this exact text box is clickable.”

### Pros

- Labels are essential for semantic tasks.
- Needed to choose among dense podcast rows.
- Helps detect search fields and placeholder hints.
- Helps label icon-only buttons when accessibility is absent.
- Gives the cloud escalation path better context without sending raw pixels only.
- Can run on-device if the model is small and quantized.

### Cons

- OCR alone gives text boxes, not clickability.
- Text may be inside a larger clickable row.
- Small fonts and stylized map labels can hurt accuracy.
- OCR latency and memory can grow quickly if run full-screen at high resolution.
- License details vary by project and model weights; need to avoid GPL components in shipped modules.
- False positives: many visible texts are not controls.

### Latency on Tensor G2 / G3

Possible within 120 ms p50 if carefully constrained. My expectation [uncertain]:

- At 640 px width, using a small text detector plus tiny line recognizer, p50 could be around 40–120 ms.
- At 1080 px full-screen, p95 could exceed 250 ms unless heavily optimized.
- If only OCR-ing candidate regions from accessibility/geometry, latency is easier to control.

So the best design is likely **conditional OCR**, not unconditional full-screen OCR every frame.

### Behavior on Spotify dense lists

Essential. The geometric problem is finding rows; OCR solves the label problem. For “find a new AI-related podcast,” the agent needs row text. The detector can return many labeled rows with medium confidence. The session can either pick candidates locally or escalate with candidate labels.

### Behavior on live map

Useful but not decisive. Map labels may include road names, turn instructions, destination names, and nearby POIs. These help escalation but usually are not tap targets. The detector should treat map text mostly as context, not control proposals.

### Verdict

Should be in v0, but constrained to candidate regions and reduced resolution.

---

## 1.5 Object detectors

### What it is

Neural box classifiers for UI elements. Relevant families:

- YOLOv8n / YOLOv9n / YOLOv10n [uncertain]
- RT-DETR variants [uncertain]
- SSD MobileNet [uncertain]
- FCOS [uncertain]
- DETR-family models [uncertain]
- PaddleDetection models [uncertain]
- NCNN/TFLite exported YOLO-like models [uncertain]

Potential classes:

- `button`
- `field`
- `icon`
- `link`
- `toggle`
- `list_row`
- `map_control`
- `chrome_button`
- `unknown_tap_target`

### Contract mapping

- `bbox`: detected box.
- `center`: tap point.
- `type`: class.
- `label`: usually absent unless fused with OCR.
- `confidence`: model score, possibly adjusted by overlap with accessibility or shape evidence.

### Pros

- Can detect icon-only controls.
- Can work on canvas surfaces if trained.
- Good for map chrome: zoom, recenter, layers, search, navigation.
- Can detect dense rows even when each row looks similar.
- Owner has real detector fine-tuning experience.
- Small int8 models may meet latency budget.
- Gives a general fallback when accessibility is weak.

### Cons

- Needs training data.
- Class definitions are semantically fuzzy.
- “Clickable” is not the same as “looks like a button.”
- Small objects require enough input resolution; 320 px may miss 30–40 px controls, while 960 px may be slow.
- License issues are real. Ultralytics YOLOv8 is AGPL-3.0 [uncertain]. For an internally shipped OS module, that may be acceptable, but it must be a conscious decision. If product distribution triggers broader obligations, use a permissively licensed detector codebase or export to a runtime where the AGPL code does not ship [uncertain].
- False positives can cause false taps if confidence is overtrusted.
- Labels still need OCR.

### Latency on Tensor G2 / G3

A small quantized 640-input detector should plausibly be within 120 ms p50 on Tensor G2 if optimized with XNNPACK/NNAPI or a native runtime [uncertain]. A 320-input model may be faster but weaker on small icons. A 960-input model may exceed budget.

On-device inference options without Play services:

- TensorFlow Lite + XNNPACK/NNAPI [uncertain]
- NCNN [uncertain]
- MNN [uncertain]
- custom C++ runtime with ARM NEON [uncertain]

The exact runtime should be benchmarked early. The design should still work if NNAPI does not route to the NPU efficiently.

### Behavior on Spotify dense lists

Useful for row detection and icon detection.

---


---

## Continuation

The first completion stopped after the sentence "Useful for row detection and icon detection." because `finish_reason` was `length` (8000 completion tokens, reasoning included). This section is a second call to the same model and endpoint, asked only to continue from that point. `reasoning_effort` was set to `low` on this call so the remaining sections would fit. The first raw response is still `research/qwen-raw-response.json`. This call's raw response is `research/qwen-raw-response-2.json`.

| Field | Value |
|---|---|
| Script | `/tmp/launch-koboldcpp.sh` |
| Model | `koboldcpp/Huihui-Qwen3.8-27B-abliterated-UD-Q4_K_XL` |
| Endpoint | `http://127.0.0.1:5001/v1/chat/completions` |
| Date | 2026-10-13 |
| max_tokens | 8000 |
| reasoning_effort | low |
| HTTP status | 200 |
| finish_reason | length |
| elapsed_s | 1129.9 |
| usage | `{"prompt_tokens": 6000, "completion_tokens": 8000, "total_tokens": 14000}` |

Useful for row detection and icon detection. A detector could emit repeated `list_row` boxes with confidence, and separate small icons such as follow, bookmark, share, or play controls. It likely cannot provide labels without OCR. For Spotify, the semantic selection still requires text: the detector returns many rows, and the session either chooses among labeled candidates locally or escalates. If every row is clickable, a high-confidence row box may be enough for the easy tap. Choosing the correct row is not primarily the detector’s job. Dense same-type lists are where class design matters: if the model only has `button`, all rows may collapse into the same class and become less useful. A `list_row` class helps, but it also increases training burden.

### Map behavior

Weaker unless trained specifically on map chrome. Google Maps and Organic Maps expose few standard widgets. Roads, pins, labels, turn instructions, and map overlays are mostly canvas. The detector should detect the few control-like objects that are actually useful for the loop: zoom in, zoom out, recenter, layers, search, navigation FAB, close/back, maybe turn-instruction cards or bottom action bars. Those boxes let the actuator pan, zoom, recenter, or open search. It should not infer “right turn here” from pixels. For map questions, the detector’s proposals are map controls, not spatial evidence. Spatial understanding belongs in the session/cloud escalation path, using GPS, heading, a sequence of cropped map views, and possibly multiple screenshots after panning/zooming.

### Verdict

Object detectors are a strong second stage and eventually necessary for icon-only controls, canvas UI, and dense unknown layouts. They should not be the sole v0 because they require data, calibration, false-tap risk management, and license decisions. Use them as a fallback when accessibility yields no plausible node, when there are small isolated controls outside accessibility coverage, or when the screen is mostly canvas. Latency is plausible with a small int8 640-input model, but benchmark early. License caveat: Ultralytics YOLOv8 is AGPL-3.0 [uncertain]. Other detector codebases are Apache/MIT-family [uncertain]. For an internal OS module, AGPL may be acceptable, but it must be a deliberate product decision. If the detector runtime ships AGPL code broadly, consider exporting to a permissive runtime or using a permissively licensed detection stack.

---

## 1.6 Small on-device multimodal models

### What it is

Tiny vision-language or visual-question-answering models that can look at a screenshot or cropped candidate regions and output JSON, labels, or local semantic decisions. Relevant families from memory [uncertain]:

- MobileVLM / MobileVLMv2
- LLaVA-style 0.5B–1B variants
- Phi-3/Phi-3.5 mini with vision support
- Qwen-VL tiny variants
- MiniGPT-style mobile models
- SigLIP/TinyCLIP visual encoders paired with small LLMs
- LayoutLM-family models for text+image layout understanding
- Tiny UI-TARS / SeeClick / CogAgent / OS-Atlas-style agents [uncertain]

The practical on-device pattern is not full-screen VLM every frame. It is:

1. Detector/accessibility pipeline emits candidate boxes.
2. OCR emits candidate labels.
3. A tiny VLM is called only when there is a semantic ambiguity among candidates.
4. It answers a constrained local question: “Which of these labeled rows best matches ‘new AI podcast’?” or “Is this map crop showing a right-turn option near the property entrance?”

### Contract mapping

- `bbox`: usually inherited from candidate set.
- `center`: inherited or chosen from candidate center.
- `type`: mostly unchanged, but model can refine `button` vs `list_row` vs `map_control`.
- `label`: can improve label quality or combine labels.
- `confidence`: model probability, should be fused with detector/accessibility confidence, not trusted alone.

A richer proposal object may include:

- `source`: accessibility, detector, geometry, OCR, multimodal
- `actionable`: true/false
- `escalation_reason`: low_confidence, ambiguous_semantics, canvas_map, stale_tree, secure_window
- `candidate_set_id`: group of related proposals

### Pros

- Can do local semantic disambiguation without cloud upload.
- Can answer simple spatial questions if given multiple cropped map views.
- Can combine visual layout and OCR better than pure rule fusion.
- Fits inside actuator if model size is small.
- Could reduce escalation rate on easy-but-ambiguous frames.

### Cons

- Usually slower than 120 ms for meaningful output.
- Memory pressure on 6–8 GB phones.
- Tensor G2/G3 NPU may not accelerate LLM-style workloads efficiently [uncertain].
- Quality depends heavily on prompt and candidate set.
- License varies by model and weights; some are Apache-2.0, some MIT, some non-commercial [uncertain].
- False semantic choices can cause false taps if overtrusted.
- Full-screen VLM is probably too slow and too broad for the detection slot.

### Latency on Tensor G2 / G3

I would expect a 0.5B–1B VLM to be outside the 120 ms p50 budget for full detection [uncertain]. If restricted to a few candidate crops and a very short constrained question, it might sometimes land around 100–400 ms [uncertain]. That makes it a good **optional local semantic step**, not the core `control_detect` detector.

### Spotify behavior

Potentially useful for the semantic half. The detector/accessibility/OCR pipeline returns many labeled podcast rows. A tiny VLM can pick likely candidates matching “new AI-related podcast” or “AI periodical” without uploading raw pixels. It should not be expected to find rows from scratch; it should rank candidate rows.

### Map behavior

Maybe useful for simple spatial reasoning if given a sequence of cropped map views plus GPS heading. For example: after panning/zooming, the tiny VLM could say whether a property entrance is visible on the far side. But for hard map look-around, cloud escalation is still likely better.

### Verdict

Worth building after the base detector works. Place it as a local disambiguation service inside the actuator, with a separate latency budget. Do not make it the first `control_detect` design.

---

## 1.7 Cloud-only / escalation path

### What it is

Large multimodal models or cloud UI-parsing services used when local confidence is low, the screen is ambiguous, the task is semantic, or the surface is canvas/map. Relevant families from memory [uncertain]:

- GPT-4o / Claude / Gemini multimodal APIs
- UI parsing APIs
- screenshot-based element extraction services
- UI segmentation models such as SAM-family or UI-specific segmentation
- mobile-agent models such as CogAgent, OS-Atlas, SeeClick, UI-TARS-style systems [uncertain]

The cloud path should receive more than a raw screenshot:

- redacted screenshot
- emitted local candidate boxes
- OCR text lines
- accessibility summary with class names, view IDs, states
- app package and version log
- GPS/heading for map tasks
- previous action context
- secure-window mask if applicable

### Contract mapping

Cloud output can use the same contract:

- `bbox`
- `center`
- `type`
- `label`
- `confidence`

It can also return higher-level fields:

- `recommended_action`: tap, type, scroll, pan, zoom, wait
- `reason`
- `irreversible_risk`: low/medium/high
- `needs_local_confirmation`: true/false

### Pros

- Best semantic understanding.
- Best map/spatial reasoning if given multiple views.
- Can choose correct podcast row from many similar rows.
- Can handle unknown apps without training.
- Can tolerate app updates better than a fixed local detector.
- Not constrained to 120 ms.

### Cons

- Network and privacy cost.
- Latency far above local budget.
- Should not be used for every easy frame.
- Screenshot redaction is required for privacy-sensitive screens.
- Irreversible actions still need separate trusted checks.

### Spotify behavior

Strong. The cloud model can read candidate labels and decide which podcast row matches “new AI-related podcast.” It can also decide whether to follow or play, subject to the trusted irreversible/action policy.

### Map behavior

Stronger than local vision for spatial questions, especially if it receives a sequence of panned/zoomed crops plus GPS/heading. The agent should pan/zoom itself based on local map-control detection plus session planning. The cloud path should answer whether the relevant feature is visible or where to look next, not operate directly on a single static screenshot.

### Verdict

Expected and necessary. The local detector should make escalation cheaper by providing candidates, labels, and confidence, not replace it.

---

# 2. Recommended v0

The smallest design I would actually ship first is:

## Accessibility-first + conditional OCR + row geometry

This is the best risk-adjusted v0 because it needs no neural model, uses the strongest structured Android signal available, and still covers dense list geometry.

### v0 pipeline

Input:

- current screenshot
- foreground window accessibility tree
- app package/version metadata
- FLAG_SECURE mask/window info
- optional previous action context

Steps:

1. **Collect accessibility nodes**
   - Filter to visible bounds in foreground window.
   - Exclude secure-window regions from proposals unless explicitly allowed.
   - Keep nodes with `isClickable`, `isEditable`, `isChecked`, `isCheckable`, useful text, contentDescription, or plausible view IDs.

2. **Classify explicit nodes**
   - clickable + non-editable → `button` or `icon`
   - editable → `field`
   - checkable → `toggle`
   - clickable image/icon-only node → `icon`
   - clickable list-like node → `list_row` if evidence exists
   - text-only node inside likely button/list context → lower-confidence `other`

3. **Compute coverage gaps**
   - If explicit nodes cover a reasonable set of controls, mark screen as standard UI.
   - If there are many repeated visual rows but few nodes, mark dense-list gap.
   - If screen is mostly one large canvas node with few chrome nodes, mark map/canvas gap.

4. **Run low-res geometry only when needed**
   - Downscale to 640 px width [uncertain this is optimal].
   - Detect horizontal row bands using projection profiles, separators, and repeated layout.
   - Detect small isolated icon-like components.
   - Detect high-contrast button rectangles.
   - Skip heavy contour work unless candidate count is small.

5. **Run conditional OCR**
   - OCR only candidate regions: row bands, button rectangles, icon neighborhoods, field hints.
   - Do not run full-screen OCR every frame.
   - Use OCR boxes to attach labels to row candidates.
   - Merge multiple text lines inside one row into a combined label.

6. **Fuse proposals**
   - Prefer accessibility for clickability.
   - Use geometry to create row candidates when accessibility is sparse.
   - Use OCR to label geometry candidates.
   - If a geometry row contains multiple accessibility nodes, emit one `list_row` proposal with combined label.
   - If an accessibility node is inside a geometry row but is clearly a child control, keep the smaller node as a separate higher-priority control.
   - If a small shape has no accessibility and no OCR label, emit `icon` or `other` with lower confidence.

7. **Output proposals**
   - Top N by confidence and actionability.
   - Include source: accessibility / geometry / OCR / fused.
   - Include escalation reason if confidence is low or screen is canvas/map.
   - Do not decide user goal.

### v0 contract extensions

I would add a few fields beyond the minimum because they improve the loop:

- `source`: where proposal came from
- `actionable`: whether the actuator can reasonably tap/type here
- `group_id`: for list rows and related controls
- `escalation_reason`: low_confidence, ambiguous_semantics, canvas_map, stale_tree, secure_window, unknown_app
- `redact_hint`: if region may be sensitive before upload

These are cheap to compute and help the session avoid bad escalation packets.

### Why this v0

It avoids the biggest early risks:

- no training data collection required
- no AGPL detector decision forced immediately
- no full-screen OCR latency problem
- uses Android’s strongest clickability signal first
- still handles Spotify row geometry via projection/OCR
- handles map surfaces by detecting map chrome and flagging the rest for escalation
- stays inside the actuator without Play services

The main weakness is icon-only/canvas UI. That is exactly where a later object detector should be added.

### v0 latency expectation

Accessibility node collection should be fast. Conditional geometry and OCR at 640 px width should plausibly fit 120 ms p50 and 250 ms p95 on Tensor G2 [uncertain]. The safest implementation gates expensive steps behind coverage gaps rather than running all modules every frame.

---

# 3. Ranked top 3 designs

## #1 — Accessibility-first + conditional OCR + row geometry — **v0**

Best first ship. Lowest risk. No training. Good for standard UI and dense lists. Weak on icon-only/canvas UI, but explicitly flags those cases for fallback/escalation.

Strengths:

- fastest to implement
- highest precision for explicit nodes
- natural labels
- good row geometry for Spotify
- safe privacy behavior
- no license complexity beyond OCR/AOSP components

Weaknesses:

- app-dependent
- weak for maps
- can miss icon-only buttons
- relies on accessibility being present and fresh

This is the design I would put on the live benchmark first.

---

## #2 — Add small quantized object detector for canvas/icon fallback

Add a small int8 detector with classes such as:

- `button`
- `field`
- `icon`
- `toggle`
- `list_row`
- `map_control`
- `chrome_button`
- `unknown_tap_target`

Use it when:

- accessibility coverage is low
- repeated rows are visually obvious but not exposed
- small isolated icons exist outside accessibility bounds
- screen is mostly canvas/map
- map chrome detection is needed

This is the strongest neural addition after v0. It should not be the only proposal source. It should be fused with accessibility and OCR.

Strengths:

- handles icon-only controls
- handles map chrome
- useful for dense unknown layouts
- owner has detector fine-tuning experience
- likely inside latency budget if small enough

Weaknesses:

- needs data
- license decision
- false tap risk
- small-object sensitivity
- labels still require OCR

If I had to pick one next engineering milestone, this is it.

---

## #3 — OS/framework clickability instrumentation and touch-region priors

Because VOS builds and signs the image, we can instrument AOSP to expose more clickability evidence than public accessibility. This includes:

- logging view IDs, classes, touch delegates
- capturing `OnTouchListener` regions where feasible
- recording draw-operation hotspots
- exposing framework-level semantic tags
- maintaining per-app clickability priors from historical behavior
- improving FLAG_SECURE window handling at compositor level

This is not a replacement for v0. It is a platform-level upgrade that makes both accessibility and future models safer.

Strengths:

- can improve precision over time
- can learn app-specific tap regions
- can reduce false taps by requiring learned or explicit clickability evidence
- can provide better map chrome detection if controls are views
- fits with long-term ownership of the OS image

Weaknesses:

- more engineering
- must track AOSP/GrapheneOS changes
- privacy-sensitive
- may leak internal IDs across app updates
- benefit may take multiple benchmark cycles

This is my preferred third design because it compounds. A tiny on-device multimodal model is also useful, but I rank it below this for the detection loop because its latency and memory costs are harder to justify before the detector and OS instrumentation are working.

---

# 4. Data and training strategy

Even if v0 needs no neural training, data collection should start immediately. The first thing to build is a benchmark telemetry pipeline.

## What to collect

For every live benchmark frame:

- app package name
- app version, if available [uncertain how stable this is]
- timestamp
- screenshot, stored locally
- redacted screenshot for escalation testing
- foreground window info
- FLAG_SECURE mask
- accessibility tree snapshot
- node count
- explicit clickable/editable/checkable node count
- geometry candidates
- OCR candidates
- fused proposals
- oracle tap point, if known
- whether local proposal covered oracle tap
- confidence of covering proposal
- source of covering proposal
- false-tap overlap statistics
- escalation decision
- action taken
- result: success/failure/unknown
- GPS/heading for map tasks

Store raw telemetry locally by default. Cloud upload only for redacted debugging or explicit benchmark review.

## What must stay on the phone

- screenshots, unless redacted/uploaded for benchmarking
- accessibility trees
- view IDs
- OCR text
- learned per-app clickability priors
- model weights
- inference calibration logs

For production, `com.vos.actuator` has no network permission, so all of this naturally stays local unless a future redaction/telemetry service is explicitly built.

## Handling app updates without version pinning

The pipeline must degrade gracefully when apps change UI.

Rules:

1. Never hard-code class names as the only signal.
2. Treat view IDs as evidence, not truth.
3. Prefer states such as clickable/editable/checkable over class strings.
4. Use OCR and geometry as fallbacks.
5. Log app version changes and benchmark coverage deltas.
6. For learned detectors, track coverage decay per app/version bucket.
7. If a learned detector suddenly misses rows that used to be detected, fall back to accessibility/geometry/OCR.
8. Periodically retrain or fine-tune with recent live frames.

No version pinning means the benchmark oracle should be task-level: “podcast plays,” “map question answered,” “settings option selected,” not “this exact node exists.”

## Training strategy if adding object detector

### Initial data sources

- v0 telemetry from live Spotify, Maps, Organic Maps, settings, dialogs, search fields, icon buttons.
- Screenshot crops around high-confidence accessibility nodes.
- Pseudo-labels from fused proposals where accessibility + geometry + OCR agree.
- Manual review of low-agreement cases.
- Hard negative examples: visible boxes that are not tappable.
- Dense list rows labeled as `list_row`.
- Map chrome controls labeled separately.

### Class strategy

Start small:

1. `button`
2. `field`
3. `icon`
4. `toggle`
5. `list_row`
6. `map_control`

Later split if needed:

- `search_button`
- `fab`
- `zoom_in`
- `zoom_out`
- `recenter`
- `layer_button`
- `nav_control`

Do not over-fragment classes before seeing benchmark failure modes.

### Annotation policy

- Label clickable UI targets, not every visual rectangle.
- For list rows, label the whole row if row is the natural tap target.
- If both row and child icon are tappable, label both but set hierarchy/group ID.
- For map surfaces, label only chrome/control elements unless a specific overlay card is interactive.
- For secure screens, either mask or exclude depending on privacy policy.

### Fine-tuning cadence

I would use continuous fine-tuning rather than one-shot training:

1. Run live benchmarks weekly or after major app updates.
2. Export low-confidence/failed frames.
3. Review and annotate only failure-relevant crops.
4. Fine-tune latest weights with recent data plus a replay buffer of older hard cases.
5. Evaluate against frozen task-level oracles.
6. Ship new weights only if task success improves and false taps do not regress.

This fits the owner’s detector fine-tuning experience. The key is to treat detector quality as a live system metric, not a one-time model release.

## Evidence to gather even if v0 needs no training

If v0 is pure accessibility+OCR+geometry, I would still collect:

- coverage stats by app and screen type
- oracle tap recall at confidence thresholds 0.5 / 0.7 / 0.9
- source breakdown: how many oracle taps covered by accessibility vs geometry vs OCR vs fusion
- latency histograms for full pipeline and each module
- false tap overlap rate
- escalation rate by screen class
- Spotify row detection recall
- Maps chrome button recall
- dense same-type list ambiguity rate
- stale-tree incidence
- FLAG_SECURE handling correctness

These numbers decide whether a neural detector is worth adding and what classes are most needed.

---

# 5. Risks

## Latency

Main latency risks:

- full-screen OCR
- contour extraction on complex screens
- accessibility tree collection for huge trees
- NNAPI/XNNPACK inference overhead
- small object detection at high resolution
- multimodal model inference

Mitigations:

- run vision at reduced width, likely 512–768 px [uncertain optimal]
- gate expensive steps behind coverage gaps
- cache only short-term window metadata, not long-lived UI state
- benchmark every module separately
- have a fast path that returns explicit accessibility nodes immediately if coverage is good

If p95 misses budget, prioritize cutting full-screen geometry/OCR before reducing confidence thresholds.

## License

Potential license issues:

- detector codebase: AGPL vs Apache/MIT [uncertain per project]
- OCR engine: Apache, MIT, BSD, GPL components [uncertain]
- OpenCV modules: mostly Apache 2.0 [uncertain]
- model weights: non-commercial licenses possible
- TFLite runtime: Apache 2.0 [uncertain]
- NCNN/MNN: Apache-family [uncertain]

Mitigations:

- keep a component license register
- avoid GPL in shipped actuator code unless product policy accepts it
- export neural models to permissive runtimes when possible
- store weights locally with clear provenance
- if using Ultralytics YOLOv8, make the AGPL decision explicitly [uncertain]

## Hidden Play-services dependency

Risks:

- ML Kit accidentally linked
- Firebase telemetry imported
- Play Feature Delivery used to fetch model
- NNAPI backend behavior assumed to require Google services on some devices [uncertain]
- AOSP vs GrapheneOS differences in NPU routing

Mitigations:

- audit dependencies in `com.vos.actuator`
- do not import ML Kit / Firebase / Play services packages
- bundle required runtimes and weights locally
- test inference on stock GrapheneOS build without sandboxed Play services
- log runtime backend used for each model run

## Missing or stale accessibility information

Risks:

- custom views expose too little
- animated UI changes faster than tree reads
- list recycling changes bounds
- secure windows hide nodes
- some apps mark controls unimportant for accessibility

Mitigations:

- re-read tree immediately before detection, not from a long-lived cache
- compare tree timestamp with screenshot timestamp
- fall back to geometry/OCR when tree is sparse or stale
- use screenshot motion detection to flag rapid UI change
- maintain app-specific freshness thresholds after measuring real cases

## Canvas and map surfaces

Risks:

- most map content is not exposed as nodes
- detector may misread roads/labels as controls
- agent may answer map question from one static screenshot
- map chrome buttons may be small and hard to detect

Mitigations:

- classify large canvas nodes as map/canvas gap
- emit only high-precision map-control proposals
- force session to treat spatial map questions as multi-step: pan/zoom/recenter, then escalate or verify
- include GPS/heading in escalation packet
- never let local detector alone authorize irreversible map/navigation decisions

## Dense same-type lists

Risks:

- every row looks like button
- type field loses meaning
- semantic selection fails
- detector returns many similar boxes
- false tap if wrong row selected

Mitigations:

- use `list_row` class when rows are the natural target
- attach OCR labels to rows
- return multiple candidates rather than one confident pick when ambiguity is high
- escalate semantic selection
- use group IDs so session knows rows belong to same list
- measure oracle success at task level, not single-box level

## Privacy of screenshots

Risks:

- photos, chat, banking, camera preview may appear
- FLAG_SECURE windows may be captured accidentally
- redaction may remove needed context
- cloud escalation exposes more than intended

Mitigations:

- use window-manager/compositor knowledge to mask secure regions because we sign the image
- default to local processing
- redact non-candidate regions before upload
- keep raw screenshots only on phone by default
- log what was uploaded, including confidence and reason
- avoid uploading OCR/accessibility internals unless needed for debugging

## False taps

Risks:

- geometry box overlaps visible text that is not clickable
- detector confuses icon with decoration
- OCR attaches label to wrong control
- accessibility node is stale
- small icon tap point is offset

Mitigations:

- require explicit clickability evidence for high confidence
- use separate confidence tiers: exact / probable / speculative
- do not auto-tap speculative proposals
- prefer accessibility node bounds over pure shape bounds
- verify proposal freshness against screenshot/window state
- for irreversible actions, use trusted path, not detector alone

---

# 6. Novel ideas and OS-level changes

Because VOS builds and signs the image, there are several platform-level changes worth making.

## 6.1 VOS UI semantic overlay

Add a lightweight framework-level annotation layer that apps can opt into, but that VOS can also infer:

- `vos_role`: button, field, toggle, row, map_control, canvas_hotspot
- `vos_actionable`: true/false
- `vos_group_id`
- `vos_sensitive_region`
- `vos_label_source`

This would be optional for third-party apps. For first-party/VOS-managed UI, it is strong. It could also be learned automatically from accessibility + behavior history.

Benefit:

- gives the detector a stable semantic target over time
- reduces ambiguity in dense lists
- helps secure/sensitive region handling
- improves escalation packet quality

Risk:

- app updates can remove or change annotations
- must not become the only signal

## 6.2 Touch-region logging at framework level

Instrument AOSP to log likely touch regions where feasible:

- view bounds with `onTouchEvent` overrides
- touch delegates
- hit-test results
- input regions registered by custom views

Then maintain per-app clickability priors:

- package/version bucket
- region hash
- historical tap success rate
- false tap rate
- class/view ID context

The detector can then output:

- `clickability_prior`: high/medium/low
- `learned_tap_target`: true/false

This does not require knowing the user’s goal. It only says “this region has historically behaved like a tappable control.”

This is probably one of the highest


---

## Continuation 2

A third call finished the novel-ideas section, which the second call cut off at "This is probably one of the highest". `max_tokens` was 3000 and `reasoning_effort` was `low`. Raw response: `research/qwen-raw-response-3.json`.

| Field | Value |
|---|---|
| Script | `/tmp/launch-koboldcpp.sh` |
| Model | `koboldcpp/Huihui-Qwen3.8-27B-abliterated-UD-Q4_K_XL` |
| Endpoint | `http://127.0.0.1:5001/v1/chat/completions` |
| Date | 2026-10-13 |
| max_tokens | 3000 |
| reasoning_effort | low |
| HTTP status | 200 |
| finish_reason | stop |
| elapsed_s | 99.5 |
| usage | `{"prompt_tokens": 535, "completion_tokens": 809, "total_tokens": 1344}` |

The model resumed mid-sentence. The tokens start at `-leverage`. Joined onto the cutoff above, that sentence is: "This is probably one of the highest-leverage additions available without requiring app cooperation."

It sits in the input pipeline, which every touch must pass through, so coverage is near-universal regardless of whether an app opts into anything.

Verdict on 6.2:

Ship it behind a flag alongside 6.1. The two are complementary: 6.1 gives a semantic annotation layer; 6.2 gives a behavioral prior. Neither should be the sole signal. If resources are constrained, 6.2 has better raw coverage because it does not depend on apps marking themselves, but 6.1 produces cleaner escalation packets when present.

## 6.3 Per-region haptic-feedback correlation

On many AOSP devices [uncertain — vendor ROMs vary], a short haptic pulse is emitted when a `View` with default click handling receives a completed tap. Instrument the haptic channel to correlate pulses with preceding touch events:

- touch-down timestamp
- touch-up timestamp
- haptic pulse detected within N ms after touch-up
- region bounds at that moment

A confirmed haptic pulse is a strong positive signal that the system treated the region as a tappable control. Absence of a pulse is weaker (the app may suppress it), but in aggregate across sessions it refines the 6.2 prior.

Risk:

- vendor overlays can replace the default haptic behavior
- some views suppress the pulse intentionally
- must not be the only signal

Verdict: add as a secondary refinement layer on top of 6.2. Low implementation cost; moderate signal quality depending on device [uncertain].

## 6.4 Input-dispatcher region snapshot

AOSP's `InputDispatcher` already routes every touch event through a single kernel-backed pipe. At that level, before events fan out to windows, log a compact region snapshot per touch event:

- window ID
- touch coordinates
- timestamp
- pointer count
- whether the event was consumed or not (from dispatcher bookkeeping)

This is upstream of 6.2 (which logs at view level) and therefore catches custom input surfaces, `SurfaceView` hit-testing quirks, and multi-window edge cases that view-level logging may miss. It adds almost no overhead because the dispatcher is already processing these fields.

Verdict: fold into 6.2's instrumentation as an additional tap point rather than a separate feature. It strengthens coverage for non-standard input surfaces without changing the detector's interface.

---

Section 6 complete.

## Appendix: exact brief sent

### System message

You are an independent systems researcher. You have no web access and no tools in this chat. You cannot look anything up. Answer from your own knowledge and say when you are unsure.

Rules:
- Flag every uncertain fact, version, benchmark number, latency number, parameter count, device spec, and software license as uncertain. Use the marker [uncertain] next to that claim.
- Do not invent citations, papers, model cards, or measurements. If you name a project, say that the name and its details are from memory and may be wrong.
- Do not pretend a number was measured on the devices in this brief.
- Give your own opinion. Do not assume a winning architecture. None is specified below.
- Write a concrete research note in markdown. No preamble about being an AI.

### User message

# Research brief: on-device UI element and clickable detection

Give your own broad research opinion on how to detect UI elements and clickable controls on a phone screen. Do not assume a preferred model or a preferred pipeline. The choice is open.

## What VOS is

VOS is an AI-operated Android phone OS built on GrapheneOS. A person talks to one agent. The agent operates the phone (taps, types, scrolls, pans, zooms) to carry out tasks in real apps.

The implementation is split into two apps:

- `com.vos.session` is the networked brain. It plans and may call cloud models. It does not hold the screen, the mic, or the gesture injector.
- `com.vos.actuator` holds the phone controls: screen capture, the accessibility service, gestures, typing, notifications. It has no network permission. Anything that must run on-device, including this detector, lives here and ships inside the actuator. It cannot call a cloud model and it must not depend on a vendor API key.

Reference devices:

- Main development phone: Pixel 7 (codename panther), Google Tensor G2.
- Also supported: Pixel 8a (codename akita), Google Tensor G3.

There are no Google Play services in the VOS stack. Do not depend on ML Kit, Play Feature Delivery, Firebase, or a runtime that is downloaded by Play services. Sandboxed Play may exist as the owner's per-app choice and is off by default. It is not a detection dependency.

We modify and sign the OS (GrapheneOS base, our product overlay, the owner's verified-boot key). OS-level and framework hooks are allowed, not only a normal user app. A stock accessibility-service design is also allowed. Do not assume a rooted shell. `FLAG_SECURE` windows must not be scraped or uploaded.

## The job: `control_detect`

One actuator tool, `ui.control_detect`, looks at the current screen and returns element proposals. It does not decide the user's goal. It does not talk to the network.

Contract. Each element is:

- `bbox` and/or `center` (pixel bounds on the screenshot, and/or a tap point)
- `type` (control kind: button, field, icon, link, toggle, other, or a richer set if you argue for one)
- `label` (optional text)
- `confidence` in [0, 1]

Latency target for this step on the Pixel 7: about 120 ms at p50 and 250 ms at p95. Screenshot capture is a separate, earlier step. Treat the 120 / 250 ms figures as the product budget for detection itself.

How the loop uses the result:

- If confidence is high and the type matches the step, the actuator taps or types at those coordinates and does not call the cloud.
- If confidence is low, the screen is ambiguous, or the step needs semantic judgment, the session may escalate. Escalation sends a redacted screenshot to a cloud multimodal model. That path is allowed and expected for hard understanding. It is not a substitute for a fast on-device proposer on the easy frames.
- Irreversible actions (send, pay, delete, commit) go through a separate trusted path. The detector must not be the only check on those.

Intents are out. Public Android intents and deep links have already proven unreliable on real apps, including map navigation. Do not recommend them as the way to operate the UI. The main path is a screenshot plus vision, with this on-device detector in the loop.

## How it will be judged

Benchmarks are live, on the current versions of real apps as the owner has them installed. Pinning an old APK and calling that success is forbidden. Logging the app version is fine. The UI will change when apps update. The detector has to tolerate that.

Two hard cases are in the test plan:

1. Spotify, dense lists. Task shape: "find a new AI-related podcast" or an AI periodical. Many rows are the same control type, and essentially every title is clickable. Finding the rows is a geometry problem. Choosing which row is "a new AI podcast" is a semantic problem. The test oracle is that the show actually plays or is followed, not that an intent was fired.

2. Live map look-around. Google Maps, and also Organic Maps, during navigation. Questions like "can I turn right here?" or "I need the entrance on the far side of this property." The answer is often off the current viewport. The agent must pan and zoom on its own. The user does not say "pan" or "zoom" or "look around." Answering from a single static screenshot is a failure, as is asking the user to move the map. A GPS and heading spoof fixture replays a drive (mock location, heading, optional speed) so the test does not need a real car. The map surface is a live canvas: roads, labels, pins, and a few chrome buttons, not a standard widget form.

Ordinary UI still matters: settings trees, dialogs, search fields, icon-only buttons, unknown apps. A rough product bar is about 70% oracle success on cold UI tasks, with a substantial share of successful steps finished on-device and not uploaded.

Phone screens here are on the order of 1080 by 2400 pixels. Icon buttons and map chrome can be only a few dozen pixels on a side.

## A skill you may use, not a conclusion

The owner has real experience fine-tuning YOLO-family detectors. You may take that as an available skill. It is not an instruction to pick YOLO, and it is not an instruction to avoid YOLO.

## What to write

Write your own research. Cover all of the following:

1. Solution families, broadly. For each family: what it is, what it can output against the contract above, pros, cons, whether it can plausibly meet about 120 ms p50 on a Tensor G2 or G3 phone with no Play services, license issues, and how it behaves on the Spotify list and on a live map. Include non-neural methods, accessibility or view-hierarchy data, OS and framework hooks, classic vision, object detectors, text recognition, and any small multimodal model you believe could run on these phones. If a family is too slow for this slot, say so and place it only on the cloud escalation path.

2. A recommended v0. The smallest design you would actually ship first, and why.

3. A ranked top 3. Mark which one is the v0.

4. Data and training strategy. What to collect, what must stay on the phone, how to cope with apps that update (no version pinning), and how the owner's detector fine-tuning experience fits. If your v0 needs no training, say what evidence you would still gather.

5. Risks. Latency, license, a hidden Play-services dependency, missing or stale accessibility information, canvas and map surfaces, dense same-type lists, privacy of screenshots, and false taps.

6. Novel ideas. Include OS changes that are worth making because we build and sign the image, if you think of any.

Be concrete. Name algorithms and projects when you remember them, and mark those memories [uncertain]. Prefer a design that is still right if a remembered number is wrong. Do not pad, and do not refuse the question for lack of web access. State the uncertainty and give the opinion anyway.

