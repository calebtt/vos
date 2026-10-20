# session — `com.vos.session`

Networked brain: voice, planner, egress. **No `RECORD_AUDIO`, accessibility, NLS, camera, location, SMS/call or privileged permissions** (TARGET-PLAN §3.1, §5.2; M1-AUD-02 checks it on a device).

**Voice turns (§3.2).**

- `SessionTurns` — the session's side of a turn: on `onTurnOpened` a backend reads the mic pipe and writes the speaker pipe on its own thread, reports `turnReady` when its vendor session is up, and a turn the session ends is reported with `endTurn` before its pipes close (the actuator then records the session's reason). A broken pipe means the actuator ended the turn and is not reported back. `ActuatorLink` binds the actuator through PeerLink.
- `ModelPolicy` — ModelRouter v0 (§3.2.1): `res/raw/model_policy.json` holds the registry and, per task class, the ordered backends; the first available one is used (a vendor backend needs its key). Only `chat_voice` is bound: `xai:grok-voice-3.0`. Each pick is audited (`model.pick` in `files/audit/audit.jsonl`). Debug builds can put an id first with `files/debug/chat_voice` (e.g. `debug:echo`, `EchoBackend`, used by `eval/m1/turn-pipes.sh`).
- `XaiRealtimeBackend` — `chat_voice` on `wss://api.x.ai/v1/realtime` (`XaiRealtime` is the protocol: 16 kHz PCM both ways, server VAD, its own event names). The mic is buffered from the summon and streamed once the session is configured; reply audio is written paced to real time plus 300 ms, so a barge-in drops what has not been sent; the turn ends after 8 s of quiet following a reply, 10 s with no speech, 3 minutes, a vendor error, or the actuator's close. The key is read from `KeyVault` per turn and goes only into the Authorization header. The vendor serves its default model for unknown names, so the served model is logged next to the requested one. The instructions carry the phone's local time.
- Tools: `res/raw/fast_tools.json` holds the schemas the model sees (`time_get`, `volume_set`, `volume_step`, `media_control`, `end_turn`). A call goes to the actuator (`ActuatorLink.callTool`, a two-way Binder call off the WebSocket thread) and its result, with the actuator's VERIFIED / UNVERIFIED / FAILED, goes back as a `function_call_output` followed by `response.create`. `end_turn` stays in the session and ends the turn once the goodbye has played. No idle end while a tool call is in flight. The instructions tell the model a result's status is the truth (P3).
- `TranscriptLog` — owner and reply transcripts in `files/transcripts/transcripts.jsonl` (never logcat; 30-day retention comes with the task record).

**M0 skeleton and keys:**

- `MainActivity` — launcher entry (the chat window lands in M1). Takes the xAI key by paste; the window is `FLAG_SECURE`.
- `SessionService` — the actuator's Binder entry (`IVosSession`, from `:ipc`), guarded by the `com.vos.permission.ACTUATOR` signature permission and by `BinderGuard` (exact package, own cert, grant table); refusals go to the session's local `files/audit/audit.jsonl` until audit v1 forwards them (§5.3).
- `KeyVault` — vendor keys (§5.4), one file per vendor in `files/keys/`, sealed with AES-256-GCM (`AesGcmSealer`) by a non-exportable Android Keystore key (`KeystoreKeys`: StrongBox where present; Phone B uses StrongBox). The vendor id is the GCM associated data. Not bound to an unlocked device. Values are never logged.
- `KeyImport` — debug builds only: a key pushed into `files/key-import/<vendor>` is sealed into the vault, the file is overwritten and deleted, and the directory removed. Push it through stdin so it never appears on a command line:

  ```bash
  printf '%s' "$GROK_API_KEY" | adb shell "run-as com.vos.session sh -c 'umask 077 && mkdir -p files/key-import && cat > files/key-import/xai'"
  adb shell am start -n com.vos.session/.MainActivity   # imports on start; the window shows "saved"
  ```

- `Egress` — the session's only HTTP and WebSocket client (§5.8): an interceptor refuses hosts not in `EgressAllowlist` (`api.x.ai` in v0) before any connection, the DNS resolver refuses them too, redirects are not followed, and no proxy is used. `RequestLog` writes one line per request or WebSocket connection to `files/egress/requests.jsonl` (host, method, path without the query, bytes out and in, status, `turnId`, outcome; never headers or bodies), rotating past 2 MB.

OkHttp is the session's only external library; the policy check keeps HTTP libraries out of the actuator. One OkHttp dex string names a Play package without using it; `ci/forbidden-exceptions.txt` documents that exception.

Tests: `./gradlew :session:testDebugUnitTest` (`EgressAllowlistTest`, `KeyVaultTest`, `EgressTest`, `ModelPolicyTest`, `XaiRealtimeTest`, and `XaiRealtimeBackendTest` against a scripted fake of the xAI endpoint on MockWebServer); on a device, `eval/m1/auth-01.sh` (M1-AUTH-01), `eval/m1/turn-pipes.sh` and `eval/m1/voice-roundtrip.sh` (the spoken round trip, M1-KEY-01 debug scan, M1-AUD-02).
