# Research: on-device UI control detection for VOS (control_detect)

Read `/home/caleb/vos/docs/TARGET-PLAN.md` (§3.5.1 hybrid pipeline, §3.5.2 tool surface, §3.2.1 ModelRouter, M3 eval). Do web research (current as of Oct 2026).

Goal: pick candidates for the actuator's on-device `control_detect` model on Pixel 7 (Tensor G2) / Pixel 8a (Tensor G3), GrapheneOS, no Play services, no network from the actuator.

Output needed per screenshot: tap coordinates (bbox/center), control type (button/field/icon/link/toggle/list row/...), optional label, confidence.

Questions:
1. Which current models/projects do this well? Consider OmniParser (v2+), UIED, YOLO-family UI detectors (and whether stock YOLO classes cover UI buttons — they generally don't; what UI datasets exist: Rico, VINS, WebUI, etc.), Ferret-UI, ScreenAI, ShowUI, UI-TARS small, GUI grounding models, Florence-2, PaliGemma, small VLMs.
2. Which can run on-device on Tensor G2/G3 under ~300 ms per frame (TFLite/LiteRT, ONNX Runtime, NNAPI/GPU delegate, quantized int8)? Model sizes and licenses (commercial-friendly?).
3. Dense-target problem (e.g. Spotify list where every title is clickable): how do detectors + ranking handle it? When to escalate to cloud multimodal.
4. Caleb has a fine-tuned YOLO model for a similar task: what would it take to fine-tune YOLO for UI controls (datasets, label schema matching the type list, expected accuracy)?
5. Should a11y node bounds be merged as a cheap extra signal? Pros/cons on Compose/canvas apps.
6. Benchmark design for M3-BENCH: live current apps, no version pinning, metrics (detection mAP vs task success).

Deliver: a ranked shortlist (top 3 + a fallback), recommended v0 pick and why, a fine-tune path for YOLO, risks, and open questions.

Write the report to `/home/caleb/vos/research/UI-DETECT-RESEARCH-2026-10-12.md`. Also include the full report in your final message.
