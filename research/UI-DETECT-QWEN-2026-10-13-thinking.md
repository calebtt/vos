# Qwen thinking trace — UI detect research

| Field | Value |
|---|---|
| Script | `/tmp/launch-koboldcpp.sh` |
| Model | `koboldcpp/Huihui-Qwen3.8-27B-abliterated-UD-Q4_K_XL` |
| Endpoint | `http://127.0.0.1:5001/v1/chat/completions` |
| Date | 2026-10-13 |
| Companion | `UI-DETECT-QWEN-2026-10-13.md` |

This file is the reasoning channel only. It is not the research answer.

---

<!-- field: reasoning_content -->
We need answer user's research brief. Need produce final markdown. Need think thoroughly. Need satisfy all sections: solution families broadly, recommended v0, ranked top 3, data/training, risks, novel ideas. Need be concrete, name algorithms/projects with [uncertain], opinions. No web. Need consider Tensor G2/G3 phones, Pixel 7/8a, GrapheneOS no Play services, actuator on-device no network. Detection contract bbox/center,type,label,confidence. Latency 120 ms p50 detection. Screen 1080x2400. Need cover non-neural, accessibility, OS hooks, classic vision, detectors, text recognition, small multimodal. Also Spotify dense lists, live maps. Need recommend v0 smallest. Need include owner fine-tuning YOLO skill. Need avoid preferred model? We can recommend hybrid v0 maybe accessibility + OCR + simple detector? Need rank top3. Need be opinionated but open. Need mention license. Need maybe TFLite/NNAPI/CPU. On-device inference options: TensorFlow Lite with XNNPACK/NNAPI, NCNN, MNN [uncertain]. Since no Play services, can bundle native runtime. Tensor G2 NPU maybe via NNAPI? Actually Pixel 7 Tensor G2 has Edge TPU? NNAPI might route to CPU/GPU/NPU [uncertain]. Without Google ML Kit, use TFLite models. Need ensure no vendor API key. Accessibility service view hierarchy can provide clickable nodes, bounds, content descriptions, text. But many apps canvas/maps not exposed; stale? In VOS we control OS, can instrument framework? Need note that accessibility info from app process may be private? It's allowed. Can hook Window/View to get drawables? FLAG_SECURE must not scrape/upload. Maybe accessibility can still see? Need not upload? It says windows must not be scraped or uploaded. Probably screen capture avoids secure surfaces or masks them. Detection on current screen maybe only visible non-secure? Need risk.

Need solution families:
1 Accessibility / view hierarchy. Output: nodes with bounds, class, clickable, editable, checked, text, contentDescription, roleDescription (API 29). Pros exact, fast (<5 ms), no training, labels. Cons depends app exposing; lists virtualization only visible rows; maps/canvas limited; icons contentDescription missing; same-type list needs geometry + text; FLAG_SECURE? latency easily. Can meet. Behavior Spotify: RecyclerView rows likely exposed as items with text? Might be clickable? Could get each title. Map: few chrome buttons, canvas not.
2 OS/framework hooks: custom window manager/view instrumentation. Because build and sign image. Can provide per-window view tree, drawables, paint operations, input regions, focus, content descriptions, maybe semantic tags. Output richer than accessibility: all views even non-important? Could capture clickables via OnTouchListener? But don't modify apps. Can collect View properties without Play services. Pros ground truth for standard widgets, can expose map UI controls if in view hierarchy; can mark canvas regions? Cons invasive, version drift with AOSP, need maintain, may leak sensitive, performance overhead. Latency OK. Need use carefully.
3 Classic vision: connected components, contour detection, Hough lines, Canny, morph, template matching, color segmentation, projection profiles. For lists: horizontal bands via row projection; detect separators; bounding boxes from contours. Buttons: rectangles/rounded rects; icon-only buttons via templates or shapes. Pros no model, explainable, works on high-contrast UI, can detect map chrome if templates. Cons brittle to style changes, text mixed, dense lists, small icons; need OCR. Latency can be low at downscaled size; full 1080x2400 maybe 20-80 ms [uncertain]. Need threshold. Behavior Spotify: row detection yes, title clickable by OCR text; map: limited, can detect zoom buttons by templates.
4 OCR / scene text: mobile EasyOCR? PaddleOCR? Tesseract? SRT? CRAFT? DBNet? MobileNetV4 + CRNN? Need on-device fast. Output text boxes, words, strings. Combine with detector/accessibility to label. Pros labels crucial for semantic choice; can identify AI podcast title; detect field hints; map labels maybe not controls but context. Cons speed, memory, small text, stylized fonts; OCR alone gives text boxes not clickability; can falsely tap text. Need classify text as control? Maybe every visible text row in list is clickable in Spotify? Not all settings. Latency: at 640 wide with small OCR maybe 50-150 ms [uncertain], could meet p50 if optimized. Need choose license: Apache/MIT? PaddleOCR Apache 2.0 [uncertain]; Tesseract Apache? Some models BSD/MIT; EasyOCR maybe MIT? Apache? Need mark uncertain. Avoid GPL. Need bundle TFLite/NCNN runtime.
5 Object detectors: YOLOv8/v9/v10/nano, RT-DETR? SSD MobileNet, FCOS, DETR variants. Output bboxes + classes + conf. Can train classes button/field/icon/link/toggle etc. Pros handles unknown UI, map chrome, dense rows (detect every row) with geometry; small nanos can run 120 ms on CPU/NNAPI. Owner skill. Cons training data, class definitions ambiguous, icon-only detection, small objects need high resolution, false positives; labels optional via OCR. Behavior Spotify: detect list rows and icons; choose semantic with OCR. Map: detect zoom +/-, layer, search, recenter buttons. Latency: YOLOv8n 640 on CPU maybe ~80-150 ms Tensor G2 [uncertain]; 320 maybe faster but misses small; 960 slow. Need quant int8. Runtime TFLite/NCNN. License: Ultralytics AGPL? Important! If owner not comfortable AGPL, use YOLOv5? Apache? Actually YOLOv8 AGPL-3.0 [uncertain]; can self-host internal product maybe OK if not distributing? We build/sign image maybe AGPL obligations? Need consider. Alternative RT-DETR? License? PaddleDetection Apache? Detectron2 BSD? Model code. Could train from PaddleDetection Apache 2.0 [uncertain] or use NCNN YOLOv8n exported? Codebase maybe AGPL. If actuator ships in OS product, AGPL may require source for modified modules? Internal phone image? Might be OK with careful license. Need risk.
6 Small multimodal VLM: Phi-3 mini? MobileVLM? TinyChat? SmolVLM? InternVL? Gemma? Qwen2-VL? On-device 3B? Output text/JSON, can reason about AI podcast and map. Pros semantic judgment; cons too slow for 120 ms on Tensor G2 without NPU (likely >1-5 s [uncertain]), memory large; better cloud escalation or on-device only for hard frames if budget expanded. Can maybe run tiny visual language model (e.g., 1-2B) but uncertain. Need include not v0 for latency.
7 Semantic/scene graph / layout parser: combine node tree + OCR + boxes to produce scene graph, infer clickable by heuristics, row grouping, form detection. Pros high precision labels, robust list rows; cons engineering heavy.
8 Input region / touchable area extraction from OS: Accessibility has click nodes; view hierarchy can expose TouchDelegate/OnTouchListener regions. For canvas maps, app may use custom touch handling not exposed. But can infer from gesture logs? Not for current detection.
9 Hybrid pipeline: recommended likely accessibility-first + OCR + optional detector fallback. Need decide v0.

Need think about product. We need fast proposer. Accessibility service is simplest and gives exact clickability. But test includes Spotify dense lists where every title clickable. Accessibility can get rows? If app exposes RecyclerView item views as clickable? In many apps, entire row is clickable via itemView.setOnClickListener, so a11y node clickable=true with bounds. If only title text is clickable? Maybe contentDescription. Could return enough. However a11y info may be missing for icon-only buttons. For maps, only chrome buttons. But the job returns element proposals, not decide goal. So v0 could be accessibility + classic row/shape/OCR. The smallest design to ship first: accessibility node dump + simple geometry + OCR labels + confidence. No trained detector initially? But top3 includes hybrid with accessibility+OCR+light YOLO fallback. Which v0? Need choose. The brief asks "smallest design you would actually ship first". If no training, fastest to implement. I'd recommend v0: Accessibility-first pipeline (A11y nodes + bounded filtering + OCR on node bounds + row/shape heuristics + output). Add light template/classic detector for map chrome? That's still small. But maybe owner skill suggests detector. Need rank top3: 1) A11y+OCR+row/geometry heuristic (v0), 2) A11y+OCR+small quantized object detector fallback (v1), 3) OS/framework view instrumentation + semantic scene graph (v2) or small multimodal on-device? Need mark v0. Which is better? Maybe top1 overall could be hybrid with small detector because robust to canvas and dense same-type; but v0 could be simpler. User wants ranked top 3. Mark which one is v0. Could have #1 = Hybrid A11y+OCR+YOLOnano (v0?) But smallest? Hmm.

Need consider latency 120 ms p50. Accessibility alone near instant. Adding OCR at 640 wide maybe could meet. Adding small YOLO maybe also meet if optimized. But v0 with only a11y+OCR less likely detect icon-only buttons. However contract type can be other and confidence lower; escalation can handle hard frames. Easy frames in standard UI: a11y+OCR good. Spotify: a11y rows + OCR titles. Map: a11y chrome + OCR labels; pan/zoom controlled by session not detector. Detector just proposes zoom buttons. The live map hard case mostly needs agent to pan/zoom; detection of canvas semantics is cloud path. So v0 can avoid trained detector initially. Need gather evidence: run benchmarks, collect false negatives, measure accessibility coverage. If detector fine-tuning experience fits later.

But top3 maybe:
1. A11y + OCR + classic row/shape heuristics (v0). Strengths fastest, no training, high precision, good lists. Weaknesses icon-only/canvas.
2. A11y + OCR + quantized YOLO-classic detector fallback (v1). Adds geometry for unknown UI/map chrome; owner skill; still within budget if 320/640 int8 TFLite/NNAPI. Weakness AGPL/data.
3. OS-instrumented view tree + scene-graph semantic proposer (v2) or small VLM? Need choose. Since we build/sign image, OS hooks can be powerful. I'd rank #3 as framework instrumentation + semantic layout graph because long-term best for clickability, but more engineering. Or #3 as on-device tiny VLM for semantic choices? But latency too slow; place only for hard frames. For top3 of control_detect families, #3 could be hybrid multimodal (detector + VLM) but likely too slow. Need include non-neural. Maybe #3 = Accessibility/view-hierarchy + classic vision (already #1)?? Need distinct.

Maybe ranked top3:
1. A11y + OCR + row/geometry heuristic (v0)
2. A11y + OCR + small YOLO/RT-DETR detector (fallback for canvas/icons)
3. OS-instrumented view tree and scene graph with semantic clickability inference (long-term)
Mark v0 = 1.

Need solution families details. Let's outline final:
Title: Research note.
Assumptions: detection excludes capture; screen 1080x2400; actuator can bundle native runtimes; no network.

Section 1: Solution families. Use table maybe.
Family 1: Accessibility/view hierarchy. Contract output: from AccessibilityNodeInfo: boundsInScreen, text, contentDescription, className, stateDescription, roleDescription, isClickable, isEditable, isChecked. Map to type: Button if clickable && role/class button; Field if editable; Toggle if checkable; Icon if contentDescription and no text; Link? roleDescription. Label text/contentDescription. Confidence from exactness (clickable=high 0.95; non-click text 0.7 etc). Pros: near zero latency, precise, labels, virtualized lists visible rows. Cons: app-dependent, missing icon-only, maps canvas limited, stale? Actually live. License: AOSP Apache 2.0. Can meet. Spotify: likely rows exposed as clickable items; good. Map: chrome only; not surface.
Family 2: OS/framework instrumentation. Because image built. Hook View/ViewGroup, Window, InputMethod, TouchDelegate, OnTouchListener, Paint ops, Drawable. Output richer: all views, semantic tags, drawables, touch regions; can mark canvas custom controls by registering hooks in framework or app overlay. Pros ground truth for standard widgets, can improve map chrome, FLAG_SECURE handling at WM. Cons maintain AOSP, performance, privacy, version drift. Latency OK. License Apache. Spotify similar, map better if views. Behavior uncertain. Need use with a11y.
Family 3: Classic vision/geometry. Algorithms: Canny+contour, morph close/open, projection profiles, connected components, template matching, Hough, corner detection, color segmentation. Output boxes and shapes; row bands. Type via heuristics: rounded rectangle=button, underline/box=field, small square=toggles, repeated horizontal band=list row. Label via OCR. Pros no model, explainable, low latency. Cons style drift, small icons, dense text. Can meet if downscale. Spotify row detection strong; map limited.
Family 4: OCR. Projects: Tesseract [uncertain], PaddleOCR (DB/MOV2 + CRNN) [uncertain], EasyOCR (CRAFT+SRT) [uncertain], SRT, DBNet, MobileNetV4+CTC. For mobile use small single-line recognizer after text box detection. Output words, lines, confidence. Pros labels, semantic search, field hints. Cons speed/memory, small/stylized text; text not necessarily control. Can meet at 640 wide. Spotify title text essential. Map labels context but not clickability. License Apache/MIT/BSD avoid GPL. Need TFLite/NCNN.
Family 5: Object detectors. YOLOv8n/v9/v10, RT-DETR? SSD MobileNet, FCOS, DETR. Output bbox class conf. Classes maybe button, field, icon, link, toggle, list_row, map_control. Pros unknown UI, icon-only, canvas chrome, dense rows geometry. Owner skill. Cons training data, AGPL for Ultralytics, small objects, false positives, label need OCR. Latency int8 320/640 on NNAPI/CPU likely within 120 ms [uncertain], full 1080 maybe not. Spotify detect rows/icons; map detect zoom/recenter. Need multi-scale/tile for small? Maybe 640 enough; 320 may miss 30 px buttons. Could do letterbox 640 and NMS.
Family 6: Small multimodal VLM. Phi-3 mini, MobileVLM, SmolVLM, InternLM-XComposer, Gemma-2B? Qwen2-VL? Output JSON or text. Pros semantic choice ("AI podcast"), map understanding. Cons latency >250 ms likely >1 s; memory; quantization complexity. Use cloud escalation or optional on-device slow path if step semantic. Could run for hard frames with p95 budget separate. License Apache/MIT? mark uncertain. Not v0.
Family 7: Scene graph/layout inference. Combine nodes, boxes, OCR, view tree to produce entities, group rows, infer clickability by touch targets, forms, lists. Pros high precision semantic labels, robust dense lists. Cons engineering.
Family 8: Input/touch region extraction / gesture prior. Learn clickable regions from past taps? On-device logs. But current detection can use historical heatmaps for repeated app screens. Pros personalization. Cons privacy, app updates.
Maybe include non-neural template library for known map apps (Maps/Organic Maps) chrome: but no version pinning, still can adapt via templates + a11y.

Need compare in table: family, outputs, latency, license, Spotify, map, verdict. Need be concrete.

Section 2: Recommended v0. I'd propose "Accessibility-first, OCR-labeled, geometry-augmented proposer" with no trained detector initially. Pipeline:
- Get accessibility root for foreground window. If multiple windows, mask FLAG_SECURE or lower confidence.
- Filter nodes: bounds intersect safe viewport, min size e.g. 16x16 [uncertain], max screen, exclude hidden/invisible.
- Map state to type and confidence: clickable => button/field/toggle based editable/checkable/class; text row in list context => other/list_row maybe 0.8; contentDescription icon => icon 0.85; non-click but plausible text => label only low 0.5.
- Use view class heuristics: android.widget.Button, ImageButton, TextView, EditText, CheckBox, Switch, SeekBar? RecyclerView item? etc.
- Build row groups using vertical overlap and horizontal bands from node bounding boxes. For dense lists: if many rows similar height in same x range, emit each row as bbox/center type list_row/button confidence medium/high if row clickable or contains text. This addresses Spotify: even if entire row not clickable? If individual title clickable, we still propose title. If every title is clickable but a11y missing clickability, row heuristic + OCR label can propose with medium confidence; session may tap first candidate or escalate to choose semantic. Need output proposals, not choose.
- Run OCR only on regions needing labels: text nodes without text/contentDescription, row bands, fields with hints, map chrome icons. Use small DB/MOV2 text box detector + CRNN or Tesseract single line. At 640 width. Add label to nearest box.
- Classic shape checks for icon-only buttons if a11y absent: rounded rectangle contours, repeated templates for common map controls (+/-). Low/medium confidence.
- Return top N (e.g. 32-64) sorted by
(Reasoning budget exceeded)
Time to respond now.


---

# Continuation thinking

| Field | Value |
|---|---|
| Model | `koboldcpp/Huihui-Qwen3.8-27B-abliterated-UD-Q4_K_XL` |
| Endpoint | `http://127.0.0.1:5001/v1/chat/completions` |
| Date | 2026-10-13 |
| reasoning_effort | low |

<!-- field: reasoning_content -->
We need continue from exact point "Useful for row detection and icon detection." Need complete rest. Need ensure no repeats sections 0-1.4 and not restart 1.5. We can just continue prose within 1.5. Then map behavior, verdict. Remaining families: 1.6 small multimodal on-device, 1.7 cloud-only VLM/segmentation maybe, perhaps 1.8 UI segmentation / layout analysis, 1.9 input/touch region hooks? But we covered OS frameworks in 1.2. Could include "small on-device multimodal" and "cloud-only". Then recommended v0, ranked top 3, data/training, risks, novel ideas.

Need same rules: mark uncertain facts/versions/numbers/licenses [uncertain]. Concrete. No padding. The user expects finish Spotify discussion just started. So continue:

"... for row detection and icon detection. A detector could emit repeated list_row boxes with confidence, and separate icon buttons. It likely cannot provide labels without OCR. For Spotify, the semantic selection still requires text: the detector returns many rows; the session escalates or uses local candidate text to choose an AI podcast. If every row is clickable, a high-confidence row box may be enough for easy step; choosing the correct row is not the detector's job. Dense same-type lists are where class design matters: if model only has button, all rows may be button, making type less useful. A list_row class helps but also adds training burden."

Then 1.5 Map behavior:
"Weaker unless trained on map chrome. Google Maps / Organic Maps expose few standard widgets. Roads/pins/labels canvas. Detector can detect zoom +/-, recenter, layers, search, navigation FAB, maybe turn instruction card. Those boxes let actuator pan/zoom/recenter. It should not infer 'right turn here' from pixels. For map questions, detector's proposals are controls, not spatial evidence."

Verdict:
"Object detectors are a strong second stage and eventually necessary for icon/canvas UI. They should not be sole v0 because data, license, false tap calibration. Use as fallback when accessibility yields no plausible node or when screen has small controls/canvas. License caveat: Ultralytics AGPL [uncertain], other codebases Apache/MIT; export weights. If AGPL internal OS module likely okay but deliberate. Latency possible with 640 int8 but benchmark."

Remaining families:
1.6 Layout/segmentation-based? Maybe "UI element segmentation / layout parsing". Could cover SAM-family? On-device small segmentation? But maybe belongs with detectors. The prompt asks include object detectors, text recognition, any small multimodal model. We owe small on-device multimodal and cloud-only. But can also include "neural scene-text + visual question answering hybrid" etc. Need not overpad. Let's include:

1.6 Small on-device multimodal models
- Tiny VLMs (LLaVA-like 0.5B/1B [uncertain]), mobile Llama with vision encoder [uncertain], Phi-3/Phi-3.5 mini [uncertain], Qwen2-VL tiny? MobileVLMv2? etc. Output JSON of elements or answer local question. Pros can fuse pixels+text+context; can answer semantic selection locally sometimes. Cons memory/bandwidth; latency probably >120 ms for full detection; likely p95 too slow unless only candidate regions and tiny model. License varies Apache 2.0/LLaVA MIT? Some weights [uncertain]. Tensor G2 NPU may not optimize LLM efficiently. Better as optional actuator reasoning step, not every-frame control_detect. For Spotify could choose row if fed candidate labels; for maps maybe answer simple spatial query if multiple screenshots? Still likely escalation. Place in actuator as "local semantic disambiguation" after detector produces candidates; budget maybe 200–800 ms [uncertain]. If it misses budget, use cloud.
- MobileCLIP/SigLIP tiny [uncertain] for icon classification; not full VLM.
- Segmentation models (SAM 2.1, U2Net, MODNet?) Probably too heavy for 120ms except tiny; can segment list rows/cards. Cloud or background.
- LayoutLM-family? Text+image layout understanding, too large? Could run tiny but uncertain.

1.7 Cloud-only / escalation path
- Large multimodal: GPT-4o/Claude/Gemini [uncertain]; UI parsing APIs; mobile app UI segmentation services. Outputs element proposals, answers spatial map questions, chooses podcast. Not inside 120ms; acceptable for hard frames. Use redacted screenshot plus candidate boxes/OCR/accessibility summary to reduce raw pixel and improve accuracy. Irreversible actions separate trusted path. Map look-around: cloud gets sequence of cropped map views + GPS heading; agent decides pan/zoom; local detector only controls map chrome.
- Cloud UI parsers (Appium-like? UiTree? Screenshot-based UI extraction) uncertain.
- Cloud segmentation (SAM, UI-TARS, SeeClick, CogAgent, OS-Atlas [uncertain]) can output clickable heatmaps; useful offline benchmark or escalation; not v0 due latency.

Could include "touch heatmap / clickability prior from draw/input hooks" under OS changes later.

Recommended v0:
Need smallest design actually ship first. I think hybrid:
- Input: screenshot, current foreground window accessibility tree, optional window metadata, FLAG_SECURE mask.
- Pipeline:
  1. Collect AccessibilityNodeInfo for visible nodes in foreground window. Filter importantForAccessibility != no, visible bounds, not FLAG_SECURE.
  2. Classify explicit nodes: clickable/editable/checkable; emit high confidence. For non-clickable text/contentDescription, emit medium if in list/button context.
  3. If explicit nodes cover enough plausible controls and there are no obvious small isolated icons outside node coverage, skip heavy vision.
  4. Else run low-res geometry: downscale to 640 width; detect row bands via projection/separators; detect high-contrast rectangles; candidate icons by component size.
  5. Conditional OCR on candidate regions only: labels from text lines inside candidate rows/buttons. Use OCR boxes to attach labels to row candidates.
  6. Fuse: prefer accessibility nodes; if candidate box overlaps accessibility node with different label, merge; if candidate box contains multiple accessibility texts (list row), emit list_row with OCR combined label; if small isolated high-confidence shape without accessibility and OCR has no label, emit icon/other lower confidence.
  7. Output top N proposals sorted by confidence and actionability. Include `source` and `coverage_gap` hint for escalation.
- No trained detector in v0, unless owner already has a small int8 detector and can calibrate quickly. But maybe object detector as "v0+"? The prompt asks recommended v0 smallest design. I can say v0 is accessibility+OCR+geometry. It may miss map chrome and icon-only buttons; add detector in v0.1/v1 once benchmark shows gap.
- Latency: should fit. Accessibility few ms; geometry/OCR conditional within budget. Full screen OCR avoided.
- Evidence gather even if no training:
  - Log every frame: app package, version [uncertain], window count, node count, explicit clickable count, geometry candidates, OCR candidates, overlap stats, FLAG_SECURE area.
  - Run live Spotify/Maps/settings benchmarks; record where actuator could have tapped on-device vs escalated.
  - Build a coverage dashboard: fraction of oracle tap points within any emitted box by source/confidence.
  - Measure false tap rate: boxes that overlap non-target elements but high conf.
  - Use this to decide whether detector class is worth adding.

Ranked top 3:
Need mark which one is v0. Top 3 designs perhaps:
1. Accessibility-first with conditional OCR and row geometry (v0) — best risk-adjusted.
2. Accessibility + small quantized YOLO-like UI/canvas detector (fallback for icons/map chrome) — likely first neural addition.
3. Small on-device multimodal selector over candidate set (local semantic disambiguation) or OS-instrumented touch-region extraction? Which is more "top 3"? They asked solution families, then ranked top 3. It may be designs. I think rank approaches:
  #1 v0 accessibility+OCR+geometry.
  #2 object detector fallback (int8 640) with list_row/map_control classes.
  #3 OS framework instrumentation / touch-region logging + clickability priors because we sign image; perhaps more powerful than tiny VLM for clickability. Or small multimodal? The prompt asks include novel ideas including OS changes; top 3 can include OS change as #3. But they asked any small multimodal model in families. In top 3 maybe: (1) access+OCR+geom, (2) add detector, (3) add instrumented framework clickability/touch region + learned click prior. Tiny VLM might be lower due latency. We can state if forced to pick a next neural step, detector beats VLM; tiny VLM is better for semantic selection on easy frames but not detection latency.
Need perhaps top 3 of solution families: 1 accessibility, 2 detector, 3 OS hooks. But v0 is hybrid not pure. Mark #1 as v
(Reasoning budget exceeded)
Time to respond now.


---

# Continuation 2 thinking

Model `koboldcpp/Huihui-Qwen3.8-27B-abliterated-UD-Q4_K_XL`, endpoint `http://127.0.0.1:5001/v1/chat/completions`, date 2026-10-13.

The user wants me to:
1. Finish the sentence "This is probably one of the highest..."
2. Finish section 6.2 if it still needs a verdict
3. Add only remaining novel OS or detection ideas not yet written
4. Stop
5. Not repeat sections 1-5
6. Not restart the note
7. Keep same uncertainty rules (mark uncertain facts, versions, numbers, licenses [uncertain])

Let me continue naturally from where the text was cut off. The sentence was "This is probably one of the highest" — in context it was talking about the value of touch-region logging at framework level. A natural completion would be something like "This is probably one of the highest-leverage additions available without requiring app cooperation."

Then I need a brief verdict for 6.2, and then any remaining ideas. Let me think about what other novel OS/detection ideas might fit here without being generic:

- Per-app touch-down dwell time distribution (framework-level)
- Cross-session clickability drift detection
- Input dispatcher region snapshots (AOSP input pipeline)
- View invalidation correlation with tap success
- Secure flag propagation in the view hierarchy (Android's `FLAG_SECURE`)
- Per-region haptic feedback correlation as a tappable-control signal

I'll keep it tight and focused, finishing the cut-off cleanly and adding a couple more ideas before stopping.
