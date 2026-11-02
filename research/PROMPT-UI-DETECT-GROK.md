# Independent second opinion: on-device UI control detection for VOS

You are giving an INDEPENDENT, BROAD second opinion. Do NOT read `UI-DETECT-RESEARCH-2026-10-12.md` until you have written your own analysis (sections 1-5 below). Then read it and add a section 6 comparing.

Context: read `~/vos/docs/TARGET-PLAN.md` (sections on ModelRouter, hybrid vision pipeline, ui.control_detect tool, M3 evals). VOS is an AI-operated GrapheneOS phone. Targets are Pixel 7 (Tensor G2) as the main dev rig and Pixel 8a (Tensor G3), with no Play services. The actuator app has no network. control_detect outputs, per screenshot, {bbox/center, type, label?, confidence} under ~120 ms p50. Caleb has his own fine-tuned YOLO model experience. Intents are out; screenshots plus vision is the main path; benchmarks run on live current apps with no version pinning.

Go broad. Use web research (current as of Oct 2026). Cover the whole solution space, not just detectors:
1. Every family of approaches: pixel detectors (YOLO, DETR, RF-DETR, YOLOX, D-FINE, etc.), segmentation (SAM-style), accessibility/view-hierarchy only, OCR-first, classical CV, small grounding VLMs, Set-of-Mark, screen-diff/temporal tricks (detect what changed, hover/press feedback), system-level hooks possible because we can modify the OS (SurfaceFlinger layers, View/Compose render node bounds, WindowManager, input dispatcher hit-testing, rendering-pipeline instrumentation), and hybrid or cascaded designs. Be creative about OS-level options that a normal app can't use.
2. For each, give pros, cons, latency on G2/G3, license, and maturity.
3. Recommend the top 3 architectures and a v0, and say where you disagree with the obvious "fine-tune YOLO" answer, if you do.
4. Data and training strategy, including self-supervised and auto-labeling from the actuator's own logs (tap → screen change).
5. Risks and open questions.
6. Compare with Claude's report (`UI-DETECT-RESEARCH-2026-10-12.md`): agreements, disagreements, and things either one missed.

Mark unverified figures [verify] and estimates [est]. Never invent numbers.
Write the report to `~/vos/research/UI-DETECT-GROK-2026-10-13.md`. Your final message should be a ~15-line summary.
