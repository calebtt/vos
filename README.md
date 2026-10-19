# VOS

> **Private repository. VOS is not a public product yet.**

VOS is an AI-operated Android session layer on GrapheneOS, with the Pixel 8a (`akita`) as the reference device. You talk, and the agent runs the phone: apps, media, settings, messages, and the audio itself, including driving in-app assistants (for example the assistant button in Google Maps) over its own duplex audio channel.

- Packages: `com.vos.session` (voice session, model, network) and `com.vos.actuator` (privileged actuator: mic, audio plane, accessibility, tools; no `INTERNET`). `com.vos.sandbox` arrives at M7.
- Tool bus: VOS Tool Bus (VTB).
- Tiers: **S** (two apps on an unmodified phone), **P** (VOS Image: GrapheneOS OTA + VOS layer, re-signed with the owner's keys), **F** (VOS Fork: GrapheneOS from source with a short patch queue, only when needed).

## Status

**M0 (Ground).** Gradle skeleton, CI and the static policy check exist. The on-device spikes and Day 0 steps are written up but not run (`docs/spikes/`, `docs/runbooks/DAY0.md`).

## Layout

| Path | What |
|---|---|
| [actuator/](actuator/README.md) | `com.vos.actuator` app: sensors and hands, no network |
| [session/](session/README.md) | `com.vos.session` app: voice, planner, egress; no sensors or actuation |
| [vtb-sdk/](vtb-sdk/README.md) | `com.vos.vtb` library for VTB providers |
| [ci/](ci/README.md) | static policy check (M0-02), its lists and negative tests |

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
