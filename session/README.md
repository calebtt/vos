# session — `com.vos.session`

Networked brain: voice, planner, egress. **No `RECORD_AUDIO`, accessibility, NLS, camera, location, SMS/call or privileged permissions** (TARGET-PLAN §3.1, §5.2). M0 skeleton:

**Voice turns.** `SessionTurns` is the session's side of a turn (§3.2): on `onTurnOpened` a backend reads the mic pipe and writes the speaker pipe on its own thread, reports `turnReady` when its vendor session is up, and its end reason goes back as `endTurn` (`ActuatorLink`, bound through PeerLink). A broken pipe means the actuator ended the turn and is not reported back. Until the realtime voice backend lands (#241), debug builds use `EchoBackend` (the mic played straight back, for `eval/m1/turn-pipes.sh`) and release builds end every turn with "no voice backend".

- `MainActivity` — launcher entry (the chat window lands in M1).
- `SessionService` — the actuator's Binder entry (`IVosSession`, from `:ipc`), guarded by the `com.vos.permission.ACTUATOR` signature permission and by `BinderGuard` (exact package, own cert, grant table); refusals go to the session's local `files/audit/audit.jsonl` until audit v1 forwards them (§5.3).
- `EgressAllowlist` — exact-host allowlist, empty by default (§5.8); the OkHttp interceptor arrives with M1-EGR-01.

Tests: `./gradlew :session:testDebugUnitTest` (`EgressAllowlistTest`); on a device, `eval/m1/auth-01.sh` (M1-AUTH-01).
