# actuator — `com.vos.actuator`

Trusted base: sensors and hands, **no `INTERNET`** (TARGET-PLAN §3.1, §5.2). M0 skeleton:

- `ActuatorService` — the session's Binder entry (`IVosActuator`, from `:ipc`), guarded by the `com.vos.permission.SESSION` signature permission and by `BinderGuard` before any argument is read: exact caller package, the actuator's own signing cert, the grant table; refusals are audited to `files/audit/audit.jsonl` (§5.3, M1-AUTH-01).
- `HandsService` (AccessibilityService) and `NotificationsService` (NLS) — stubs, exported but guarded by the system-only `BIND_ACCESSIBILITY_SERVICE` / `BIND_NOTIFICATION_LISTENER_SERVICE` permissions, so only the system can bind them (M1). `ci/policy_check.py` fails any exported service with one of these actions that lacks its guard.
- The manifest strips `INTERNET` with `tools:node="remove"`; `ci/policy_check.py` checks the built APK anyway.
- Depends on `:vtb-sdk` (the actuator hosts the VTB registry, §3.4) and `:ipc` (the session ↔ actuator contract).

Tests: `./gradlew :ipc:testDebugUnitTest` (`BinderGuardTest`); on a device, `eval/m1/auth-01.sh` (M1-AUTH-01).
