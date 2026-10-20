# ipc — session ↔ actuator Binder contract

Internal library shared by `com.vos.actuator` and `com.vos.session` (not the public VTB SDK). It holds the AIDL interfaces (`IVosActuator`, `IVosSession`) and the Binder guard every incoming transaction runs before reading any argument (TARGET-PLAN §5.3):

1. `uid = Binder.getCallingUid()`;
2. `getPackagesForUid(uid)` is exactly the expected package;
3. `hasSigningCertificate(uid, <this app's own signing cert SHA-256>)`, so release only talks to release and debug only to debug;
4. the caller's grant set from `GrantTable`.

Results are cached per UID and cleared on any package add, replace or removal. Every failure throws `SecurityException("UNAUTHORIZED")` and writes an audit line with the UID, packages and reason. `PeerLink` binds the other side and watches it with `linkToDeath`. M1-AUTH-01 runs on a device: `eval/m1/auth-01.sh`.
