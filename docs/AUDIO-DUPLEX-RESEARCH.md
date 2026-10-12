# VOS Audio Duplex Research

| Field | Value |
|-------|-------|
| Date | 2026-10-12 (dual-device note) |
| Owner | Caleb |
| Purpose | Back the AudioPlane redesign in `TARGET-PLAN.md`: intercept / redirect / manage all audio, and own a duplex channel with in-app assistants (Maps Gemini / Assistant button), not only UI taps. |
| Status | Desk research + AOSP source reads. Every API claim that decides a milestone is a **spike** in §6.0 of the plan. |

---

## 1. What "amazing" means for audio

A human with a finger and a headset can:

1. Pause, duck, route, and silence anything making noise.
2. Hear what is playing ("what did that say", "save the last 30 s").
3. Tap the mic / Gemini button in Maps (or any in-app assistant) and talk through that app's own voice UI.
4. Hear that app's spoken reply on the headset while the rest of the phone stays under VOS control.

VOS must do the same without looking like a bot: one AudioPlane owns the path, the session only speaks and plans, and the owner can always stop it. Tapping UI alone is not enough for (3)–(4). The agent needs **its own duplex audio channel with the target app**.

---

## 2. Mechanism map (verified from AOSP / docs, 2025–2026)

### 2.1 Privileged AudioPolicy (Tier P/F workhorse)

| Capability | API | Permission | Notes |
|---|---|---|---|
| Focus arbitration | `AudioPolicy.Builder.setIsAudioFocusPolicy(true)` + `AudioPolicyFocusListener` | `MODIFY_AUDIO_ROUTING` | Authoritative "who may play". |
| Capture other apps' playback | `AudioMix` + `MIX_TYPE_PLAYERS` / `MIX_ROLE_PLAYERS` + `ROUTE_FLAG_LOOP_BACK` → `createAudioRecordSink(mix)` | `MODIFY_AUDIO_ROUTING` (or MediaProjection for loopback-render). Privileged capture often also needs `CAPTURE_AUDIO_OUTPUT`. | Public `AudioPlaybackCaptureConfiguration` needs MediaProjection every session → violates P6 on Tier S. |
| Inject into another app's mic | `AudioMixingRule.MIX_ROLE_INJECTOR` (= `MIX_TYPE_RECORDERS`) + `RULE_MATCH_UID` (or session id) + `ROUTE_FLAG_LOOP_BACK` → `createAudioTrackSource(mix)` | `MODIFY_AUDIO_ROUTING` | **This is the duplex uplink.** AOSP sample: `VirtualDeviceManager` host `AudioInjector.java`. Writes to that `AudioTrack` become what the matched UID's `AudioRecord` hears. |
| Per-UID device affinity | `AudioPolicy.setUidDeviceAffinity` / `setPreferredDevicesForStrategy` | `MODIFY_AUDIO_ROUTING` | "Play it on the speaker, keep me on the headset." |
| Voice-comm playback capture | Mix flag `voiceCommunicationCaptureAllowed` + often `CAPTURE_VOICE_COMMUNICATION_OUTPUT` | Privileged | Needed if Maps / Gemini replies use `USAGE_VOICE_COMMUNICATION` or similar. Loopback-render public mixes only match UNKNOWN/MEDIA/GAME/VOICE_COMMUNICATION under restrictions; ASSISTANT / system usages need privileged rules. |
| Concurrent-record bypass | `CAPTURE_AUDIO_OUTPUT` (legacy) or `CONCURRENT_AUDIO_RECORD_BYPASS` (Android 16+) | Privileged | Lets the actuator keep the mic while another app records (Maps listening). |

**Unverified on Graphene (smoke on Phone B `panther`; confirm on `akita`):** whether a product priv-app's registered AudioPolicy receives focus callbacks, whether UID injector replaces (vs mixes with) the hardware mic, and latency for AEC. Spikes **S-04, S-05, S-27, S-28, S-31**. Both Pixel 7 and Pixel 8a are first-class Tier P targets; panther is the default flash/dev rig.

### 2.2 VirtualAudioDevice (isolate an app's I/O)

- `VirtualDeviceManager.createVirtualDevice` → `createVirtualAudioDevice(virtualDisplay, …)`.
- `startAudioInjection(format)` / `startAudioCapture(format)`.
- Permissions: `CREATE_VIRTUAL_DEVICE` (system / role-gated) + `MODIFY_AUDIO_ROUTING` for start.
- Capture respects `setAllowedCapturePolicy`; privileged playback capture is not supported through this path (docs say so).
- Needs a Companion Device Manager association id in the public SystemApi shape.

**Fit for VOS:** run a target app (or a clone of its task) on a trusted virtual display with a private mic/speaker pipe. Amazing for "talk to Maps' assistant while the main screen stays on what I was doing." Heavy setup; may need Tier F to grant `CREATE_VIRTUAL_DEVICE` without a physical companion. Spike **S-30**.

### 2.3 Android Computer Control (preview, docs dated 2026-05-13)

- OEM-preloaded assistant with `ACCESS_COMPUTER_CONTROL` requests a session for ≤6 allowlisted target apps.
- System creates a trusted virtual display; assistant gets screenshots + injected taps/swipes/text.
- User consent dialog per target app (conflicts with P6 unless pre-granted on Tier P via sysconfig / one-time setup).
- AOSP flags include `computer_control_intercept_audio` → custom audio policy with capture/injection for the session.
- GrapheneOS ships Android 17 on supported Pixels including Pixel 7 (`panther`) and Pixel 8a (`akita`) [verify each codename's current Graphene Android version] as of mid-2026 (stable ~202607), so the framework may exist; Graphene may disable or not expose OEM-only surfaces. Default S-29 smoke on panther; confirm on akita. Spike **S-29**.

**Fit for VOS:** best platform-native path for "operate Maps in the background with its own audio." Prefer this over inventing a parallel virtual-display stack **if** Graphene allows the permission on a product priv-app VoiceInteractionService. Fallback: own VirtualDevice stack (S-30) or foreground UID inject (S-27/S-28).

### 2.4 Call audio redirect (non-goal for content, useful for patterns)

- `MODE_CALL_REDIRECT` / `MODE_COMMUNICATION_REDIRECT` (Android 13+): call sinks/sources disconnected; privileged app injects/extracts.
- `CALL_AUDIO_INTERCEPTION` → `getCallUplinkInjectionAudioTrack` / `getCallDownlinkExtractionAudioRecord` / `isPstnCallAudioInterceptable`.
- VOS **still does not** capture or summarize call content (plan §9 Q7). The redirect APIs are the template for "take the pipe, put our PCM on it" that App-Assistant duplex copies for non-telephony apps.

### 2.5 Transport and per-player control (Tier S + P)

| Need | API | Tier |
|---|---|---|
| Pause / next / seek every MediaSession app | `MediaSessionManager.getActiveSessions` via NLS, or `MEDIA_CONTENT_CONTROL` (privileged) | S / P |
| Per-player pause / volume without MediaSession | `AudioPlaybackConfiguration.getPlayerProxy()` → `PlayerProxy.pause/setVolume` (@SystemApi) | P |
| Start by name | `MEDIA_PLAY_FROM_SEARCH`, MediaBrowser, own Media3 player | S |
| Stream volumes | `AudioManager.setStreamVolume` | S |

### 2.6 Hotword / assistant role

- `VoiceInteractionService` + `ROLE_ASSISTANT` (actuator holds it).
- Software hotword: `createHotwordDetector` → `HotwordDetectionService` with `android:isolatedProcess="true"` (no Pixel DSP for third-party models). Opt-in only.
- Graphene: "Hey Google" / Voice Match is not a Graphene feature; sandboxed Play + Google/Gemini as default assistant can approximate it, but then **VOS is not the default assistant**. Decision: VOS keeps `ROLE_ASSISTANT`; in-app Google buttons are driven by duplex Path A/B, not by surrendering the role.

### 2.7 What Google Maps / Gemini actually does (2025–2026)

- In navigation, tap Gemini / mic or say "Hey Google" → Gemini (or classic Assistant) handles the turn.
- On Android, Maps settings can choose Gemini vs Assistant for navigation.
- This path expects Google app / Gemini + Play services behavior. On Graphene with sandboxed Play optional:
  - **With sandboxed Play + Google/Gemini installed:** the in-app button works; VOS must duplex into *that* recording/playback (Path A/B), and must not steal the mic in a way that silences Maps.
  - **Without Play:** Maps' Gemini button is `NEEDS_GOOGLE`. Organic Maps / OsmAnd remain the no-Google navigation path; duplex still applies to any app that opens an `AudioRecord` for voice UI (Signal voice notes, WhatsApp, etc.).

Amazing does not mean "only Google." It means the **same duplex machinery** works for any app's in-app voice UI, with Maps+Gemini as the hard reference when Play is present.

---

## 3. Chosen architecture: AppAssistantDuplex

Owned by the actuator. Session never opens an `AudioRecord` or `AudioTrack` against another app.

```text
Owner: "Ask Maps what's the traffic like"
        │
        ▼
Session plans → do(app.assistant.duplex, {pkg, utterance})
        │
        ▼
Actuator AppAssistantDuplex:
  1. Ensure target foreground (or Computer Control / VirtualDevice session)
  2. ENTER_APP_ASSIST state (AudioPlane)
  3. Open CAPTURE mix (UID-matched playback → AudioRecord sink)
  4. Open INJECT mix  (UID-matched recorders → AudioTrack source)
  5. Tap in-app mic / Gemini control (a11y) OR rely on CC session
  6. Wait for target AudioRecord active (AudioRecordingCallback)
  7. Stream session TTS/vendor PCM → injector AudioTrack
  8. Stream capture sink → session (as observation audio / ASR)
  9. Detect end of app reply (silence / recording stopped / UI state)
 10. RESTORE focus, mixes, devices; oracle = spoken reply transcript or UI change
```

### State machine addition (AudioPlane)

```text
… existing IDLE/CAPTURE/CONVERSE/ANNOUNCE/NOTICE/HOLD/RESTORE …

CONVERSE ──app.assistant.duplex──► APP_ASSIST
APP_ASSIST:
  - dual mixes registered (inject + capture) for targetUid
  - VOS own CONVERSE track ducked or paused
  - offline "stop" still armed
  - no trusted-path irreversible dispatch except cancel
APP_ASSIST ──end/stop/timeout──► RESTORE
```

### Latency budget (amazing bar)

| Step | Budget |
|---|---|
| Tap → target `AudioRecord` active | ≤400 ms |
| First injector PCM after record active | ≤50 ms |
| Round-trip mouth → app reply onset audible on headset | feel native; measure p50/p95 in S-27 |
| Stop → mixes torn down, mic released | ≤300 ms |

### Security

- Inject/capture mixes are **turn-scoped** and torn down on stop / death / target UID exit.
- Captured app audio stays on device unless the owner asked in this turn (same as loopback rule).
- Session receives app-assistant audio as **quoted observation** with `origin=app_assist:<pkg>`, never as instructions.
- Irreversible acts the app might take from the spoken command still go through that app's own UX; VOS does not auto-confirm pay/send inside the app without trusted path on commit taps (existing M3 detector).

---

## 4. Tier matrix (duplex)

| Capability | Tier S | Tier P | Tier F |
|---|---|---|---|
| Own VOS voice I/O | Yes | Yes | Yes |
| MediaSession transport | Yes | Yes (+ `MEDIA_CONTENT_CONTROL`) | Yes |
| Focus / UID route / loopback media | Cooperative / No | Yes | Yes |
| **UID inject into app mic** | No | Yes (S-27) | Yes; F6 if policy blocks |
| **UID capture of app voice reply** | No (MediaProjection breaks P6) | Yes (S-28) | Yes |
| Computer Control session + audio | No | Probe (S-29) | Grant/`sysconfig` if missing |
| VirtualAudioDevice isolate | No | Probe (S-30) | Likely needed for CDM-less create |
| Maps Gemini without Play | `NEEDS_GOOGLE` | Same | Same |
| Generic in-app voice UI (non-Google) | Tap only | Duplex when app uses normal `AudioRecord` | Same |

---

## 5. Graphene / Play constraints

| Fact | Impact |
|---|---|
| Graphene = Android 17 on panther + akita (2026) [verify] | Computer Control / VirtualDevice code may be present; OEM permissions still need product allowlist. Smoke on panther. |
| Sandboxed Play optional, unprivileged | Maps Gemini needs Play + Google/Gemini apps; VOS never depends on them. |
| VOS holds `ROLE_ASSISTANT` | Do not yield the role to Google. Drive Maps' button; duplex the resulting record/play. |
| No "Hey Google" DSP path for third parties | Summon-first; software hotword opt-in only. |
| hardened_malloc + MTE | Native PCM loops (sherpa, pipes) already S-24; duplex adds continuous sink/source — include in soak. |
| `ALLOW_CAPTURE_BY_NONE` | Honest refusal; never bypass. |

---

## 6. Spike register (audio / duplex only)

Full table lives in plan §6.0. Hardest first:

| ID | Claim | Why hard |
|---|---|---|
| **S-27** | UID `MIX_ROLE_INJECTOR` + `createAudioTrackSource` feeds a live third-party `AudioRecord` (fixture app + Maps with Play) on Graphene VOS Image | Timing vs when the app opens the record; whether injector replaces mic or mixes; BT HFP interaction |
| **S-28** | Privileged loopback captures that same app's spoken reply (`USAGE_ASSISTANT` / `VOICE_COMMUNICATION` / `MEDIA`) with usable latency | Usage matching + `CAPTURE_VOICE_COMMUNICATION_OUTPUT`; AEC reference quality |
| **S-29** | `ACCESS_COMPUTER_CONTROL` (+ audio intercept flag) grantable to product VoiceInteractionService on Graphene 17; consent pre-grantable | OEM-only surface; may be stubbed or policy-blocked on Graphene |
| **S-30** | `CREATE_VIRTUAL_DEVICE` + `VirtualAudioDevice` without a physical companion accessory | CDM association requirement |
| **S-31** | Concurrent capture: actuator keeps VOS offline "stop" mic while target app records | `CONCURRENT_AUDIO_RECORD_BYPASS` / `CAPTURE_AUDIO_OUTPUT` behavior on Tensor |
| **S-32** | End-to-end "amazing" demo: headset on, music ducked, Maps nav, VOS asks traffic via Maps Gemini, reply heard, music resumes | Integration; defines the bar |
| S-04/S-05/S-06 | Existing focus / loopback media / UID affinity spikes | Still required; S-05 extended for voice-comm |

Fallback ladder if S-27 fails: S-30 → S-29 → Tier F patch **F6** (AudioPolicy / concurrent-capture allowlist for `com.vos.actuator`) → honest `NEEDS_PLATFORM` on Tier S.

---

## 7. What we will not do

- Capture or inject cellular call audio content (non-goal).
- Bypass `ALLOW_CAPTURE_BY_NONE` or `FLAG_SECURE`.
- Make Google Play / Gemini a VOS dependency.
- Yield `ROLE_ASSISTANT` to Google to "make Maps work."
- Stream app-assistant audio to the vendor without an in-turn owner request.
- Treat UI-tap-only as meeting the duplex requirement.

---

## 8. References (desk)

- AOSP `AudioPolicy.java` / `AudioMixingRule.java` / `AudioMix.java` — `createAudioTrackSource`, `createAudioRecordSink`, `MIX_ROLE_INJECTOR`, `ROUTE_FLAG_LOOP_BACK`.
- AOSP `development/.../vdmdemo/host/AudioInjector.java` — UID + session injector sample.
- AOSP `VirtualAudioDevice.java` — `startAudioInjection` / `startAudioCapture`.
- Android Developers — [Computer Control](https://developer.android.com/ai/computer-control) (updated 2026-05-13).
- Android Developers — [Capture video and audio playback](https://developer.android.com/media/platform/av-capture) — capture policies.
- GrapheneOS releases / forum — Android 17 on Pixel; sandboxed Play; no Hey Google DSP.
- Google Maps Help — Gemini in navigation (tap Gemini / "Hey Google").
