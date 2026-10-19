# actuator — `com.vos.actuator`

Trusted base: sensors and hands, **no `INTERNET`** (TARGET-PLAN §3.1, §5.2). M0 skeleton:

- `ActuatorService` — the session's Binder entry, guarded by the `com.vos.permission.SESSION` signature permission and a caller-package check before any argument is read (§5.3). IVosActuator arrives in M1.
- `HandsService` (AccessibilityService) and `NotificationsService` (NLS) — stubs, not exported; bound only by the system.
- The manifest strips `INTERNET` with `tools:node="remove"`; `ci/policy_check.py` checks the built APK anyway.
- Depends on `:vtb-sdk` (the actuator hosts the VTB registry, §3.4).

Test: `./gradlew :actuator:testDebugUnitTest` (`CallerGuardTest`).
