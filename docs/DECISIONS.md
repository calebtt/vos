# VOS decisions log

Records the M0 platform and audio decisions (TARGET-PLAN §6 M0-04, M0-05). Each entry names its evidence in `docs/spikes/` and its date. An entry stays **PENDING** until its spikes have results; nothing here is decided by default.

## D-M0-04 — Platform: VOS Image on the Graphene OTA, or the Tier F build path (#129)

- **Status:** PENDING. S-02 passed on the desk (2026-10-19: ≥4.1 GiB `super` headroom on panther and akita). S-01's image recipe (`image/build.sh`) builds and verifies on the desk; the unlock, flash and relock wait for Caleb (each wipes Phone B). S-03 needs the flashed image.
- **Evidence:** [S-01](spikes/S-01.md) (#11), [S-02](spikes/S-02.md) (#10), [S-03](spikes/S-03.md) (#12).
- **Decision rule:**
  - S-01, S-02 and S-03 all pass on `panther` → record **"VOS Image on Graphene OTA"** (Tier P, §4.4), the Graphene build number used, and the date.
  - S-01 fails → record **"Tier F build path"** (zero patches, §4.6 a) with the failing step as the reason; open the Tier F epic the same day; M1 continues on Tier S.
  - S-02 fails only → Tier P with the overlay in `system_ext` and slimmer APKs; record the measured headroom.
  - S-03 fails only → Tier P with the permission moved to a different partition XML; record which.
- **Date decided:** —
- **Decided by:** —

## D-M0-05 — Audio: actuator-owned mic, or the recorded fallback (#130)

- **Status:** PENDING (needs a phone: S-07, S-08 not run; S-27a blocked on S-01).
- **Evidence:** [S-07](spikes/S-07.md) (#17), [S-08](spikes/S-08.md) (#18), [S-27a](spikes/S-27a.md) (#44); desk plan in [AUDIO-DUPLEX-RESEARCH.md](AUDIO-DUPLEX-RESEARCH.md).
- **Decision rule:**
  - S-07 meets p95 ≤150 ms on Graphene (assist gesture, QS tile, classic BT; unlocked and locked) → **actuator-owned mic confirmed**. Otherwise record the fallback: session-UI activity as the foreground anchor.
  - S-08 pipe ≤10 ms p95 with 0 underruns, and platform AEC meets the echo bars → keep the pipe design and platform AEC. Otherwise record: larger buffers and/or WebRTC AEC3, with its CPU cost.
  - AppAssistantDuplex desk plan accepted (Caleb), and S-27+ scheduled on Phone B after S-01. If S-27a fails on the fixture → Tier F epic with F6 as a candidate; run S-30 next.
- **Date decided:** —
- **Decided by:** —
