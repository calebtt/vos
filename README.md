# VOS

> **Private repository. VOS is not a public product yet.**

VOS is an AI-operated Android session layer on GrapheneOS, with the Pixel 7 (`panther`) as the main dev and flash device and the Pixel 8a (`akita`) as the second, first-class target. You talk, and the agent runs the phone: apps, media, settings, messages, and the audio itself, including driving in-app assistants (for example the assistant button in Google Maps) over its own duplex audio channel. When you ask, that same agent also helps harden privacy — review permissions in the Settings UI, inspect VOS's declared egress, and set another app's Network/Sensors toggles and scopes (catastrophic "yes") or whether sandboxed Play is installed in the Owner profile — using the privileges the plan already gives it.

- Packages: `com.vos.session` (voice session, model, network) and `com.vos.actuator` (privileged actuator: mic, audio plane, accessibility, tools; no `INTERNET`). `com.vos.sandbox` arrives at M7.
- Tool bus: VOS Tool Bus (VTB).
- Tiers: **S** (two apps on an unmodified phone), **P** (VOS Image: GrapheneOS OTA + VOS layer, re-signed with the owner's keys), **F** (VOS Fork: GrapheneOS from source with a short patch queue, only when needed).

## Status

**M0 (Ground).** Gradle skeleton, CI and the static policy check exist. The VOS Image builder (`image/build.sh`) builds and verifies a re-signed panther OTA on the desk; flashing it waits for Caleb.

- **Day 0** ([runbook](docs/runbooks/DAY0.md)): done — the archive of old work (§5), GrapheneOS firmware for `panther` and `akita` downloaded and verified (§6), Graphene OTA certificates pinned (§7). Open, for Caleb: rotate the xAI key (§1), revoke ADB on Phone A (§3), pin UStor2 in fstab (§4), order Phone C (§8).
- **Spikes** ([docs/spikes/](docs/spikes/)): S-02 passes on the desk (super headroom measured from the factory image). S-01 desk build passes; the flash, relock and `dumpsys` legs wait for Caleb. S-03 read-only checks; S-07 QS-tile leg measured and **missed** the 150 ms bar (p95 176 ms); S-08 pipe latency met over 10,000 frames, soak and AEC not run. Open legs and the pending M0 decisions are in [docs/DECISIONS.md](docs/DECISIONS.md).

## Layout

| Path | What |
|---|---|
| [actuator/](actuator/README.md) | `com.vos.actuator` app: sensors and hands, no network |
| [session/](session/README.md) | `com.vos.session` app: voice, planner, egress; no sensors or actuation |
| [vtb-sdk/](vtb-sdk/README.md) | `com.vos.vtb` library for VTB providers |
| [ci/](ci/README.md) | static policy check (M0-02), its lists and negative tests |
| [image/](image/README.md) | VOS Image (Tier P) builder, owner-key script, overlays, Graphene OTA pins |

## Build

Needs JDK 21 and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`. Point Gradle at the SDK with `ANDROID_HOME` or an ignored `local.properties` (`sdk.dir=/path/to/Android/Sdk`). Python 3 runs the policy check.

```bash
ci/fetch-gitleaks.sh        # optional locally: pinned, checksum-verified gitleaks into .tools/
./gradlew check :actuator:assembleRelease :session:assembleRelease
```

`check` runs unit tests, lint, the policy check over the built APKs (`policyCheck`) and its negative tests (`policyCheckTests`). Release APKs build unsigned unless a release key is supplied through `VOS_RELEASE_STORE_FILE`, `VOS_RELEASE_STORE_PASSWORD`, `VOS_RELEASE_KEY_ALIAS`, `VOS_RELEASE_KEY_PASSWORD` or an ignored `keystore.properties`; debug builds use the per-machine debug key, so the two certs always differ. Never commit a keystore. CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs the same command on every push and pull request with a throwaway release key and gitleaks required. Details: [ci/README.md](ci/README.md).

## Docs

- [docs/TARGET-PLAN.md](docs/TARGET-PLAN.md) — the target plan (architecture, platform, security, milestones, spike register).
- [docs/AUDIO-DUPLEX-RESEARCH.md](docs/AUDIO-DUPLEX-RESEARCH.md) — research behind the AudioPlane and app-assistant duplex.
- [docs/TRACKING.md](docs/TRACKING.md) — how milestones, spikes, and labels map onto GitHub issues.
- [docs/DECISIONS.md](docs/DECISIONS.md) — M0 platform and audio decisions (pending spikes).
- [docs/DEVICES.md](docs/DEVICES.md) — device enrollment (M0-06).
- [docs/spikes/](docs/spikes/) — spike report templates (S-01, S-02, S-03, S-07, S-08, S-27a).
- [docs/runbooks/DAY0.md](docs/runbooks/DAY0.md) — Day 0 steps for Caleb (key rotation, ADB revocation, fstab pin, archive, Graphene downloads, OTA cert pin).
