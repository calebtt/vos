# VOS

> **Private repository. VOS is not a public product yet.**

VOS is an AI-operated Android session layer on GrapheneOS, with the Pixel 8a (`akita`) as the reference device. You talk, and the agent runs the phone: apps, media, settings, messages, and the audio itself, including driving in-app assistants (for example the assistant button in Google Maps) over its own duplex audio channel.

- Packages: `com.vos.session` (voice session, model, network) and `com.vos.actuator` (privileged actuator: mic, audio plane, accessibility, tools; no `INTERNET`). `com.vos.sandbox` arrives at M7.
- Tool bus: VOS Tool Bus (VTB).
- Tiers: **S** (two apps on an unmodified phone), **P** (VOS Image: GrapheneOS OTA + VOS layer, re-signed with the owner's keys), **F** (VOS Fork: GrapheneOS from source with a short patch queue, only when needed).

## Status

**Plan only.** No code exists yet.

## Docs

- [docs/TARGET-PLAN.md](docs/TARGET-PLAN.md) — the target plan (architecture, platform, security, milestones, spike register).
- [docs/AUDIO-DUPLEX-RESEARCH.md](docs/AUDIO-DUPLEX-RESEARCH.md) — research behind the AudioPlane and app-assistant duplex.
- [docs/TRACKING.md](docs/TRACKING.md) — how milestones, spikes, and labels map onto GitHub issues.
