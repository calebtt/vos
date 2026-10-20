# ipc — session ↔ actuator Binder contract

Internal library shared by `com.vos.actuator` and `com.vos.session` (not the public VTB SDK). It holds the AIDL interfaces (`IVosActuator`, `IVosSession`) and the Binder guard every incoming transaction runs before reading any argument (TARGET-PLAN §5.3):

1. `uid = Binder.getCallingUid()`;
2. `getPackagesForUid(uid)` is exactly the expected package;
3. `hasSigningCertificate(uid, <this app's own signing cert SHA-256>)`, so release only talks to release and debug only to debug;
4. the caller's grant set from `GrantTable`.

Nothing is cached: a package can be replaced, or its UID reused, before the package broadcast arrives, so every call asks `PackageManager` again. The services run the guard in `onTransact` and again at the top of every AIDL method, because an in-process caller reaches the Stub without `onTransact`. Every failure throws `SecurityException("UNAUTHORIZED")` and writes an audit line with the UID, packages and reason. `PeerLink` binds the other side, checks `protocolVersion()` against `Ipc.PROTOCOL_VERSION` before publishing the link (a mismatch drops it), and watches it with `linkToDeath`; up and down are decided under one lock and delivered in order on the main thread. M1-AUTH-01 runs on a device: `eval/m1/auth-01.sh`.
