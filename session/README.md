# session — `com.vos.session`

Networked brain: voice, planner, egress. **No `RECORD_AUDIO`, accessibility, NLS, camera, location, SMS/call or privileged permissions** (TARGET-PLAN §3.1, §5.2). M0 skeleton:

- `MainActivity` — launcher entry (the chat window lands in M1).
- `SessionService` — the actuator's Binder entry (`IVosSession`, from `:ipc`), guarded by the `com.vos.permission.ACTUATOR` signature permission and by `BinderGuard` (exact package, own cert, grant table); refusals go to the session's local `files/audit/audit.jsonl` until audit v1 forwards them (§5.3).
- `EgressAllowlist` — exact-host allowlist, empty by default (§5.8); the OkHttp interceptor arrives with M1-EGR-01.

Tests: `./gradlew :session:testDebugUnitTest` (`EgressAllowlistTest`); on a device, `eval/m1/auth-01.sh` (M1-AUTH-01).
