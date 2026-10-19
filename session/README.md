# session — `com.vos.session`

Networked brain: voice, planner, egress. **No `RECORD_AUDIO`, accessibility, NLS, camera, location, SMS/call or privileged permissions** (TARGET-PLAN §3.1, §5.2). M0 skeleton:

- `MainActivity` — launcher entry (the chat window lands in M1).
- `SessionService` — the actuator's Binder entry, guarded by the `com.vos.permission.ACTUATOR` signature permission and a caller-package check (§5.3).
- `EgressAllowlist` — exact-host allowlist, empty by default (§5.8); the OkHttp interceptor arrives with M1-EGR-01.

Test: `./gradlew :session:testDebugUnitTest` (`EgressAllowlistTest`).
