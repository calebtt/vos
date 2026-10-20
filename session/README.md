# session — `com.vos.session`

Networked brain: voice, planner, egress. **No `RECORD_AUDIO`, accessibility, NLS, camera, location, SMS/call or privileged permissions** (TARGET-PLAN §3.1, §5.2). M0 skeleton:

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

Tests: `./gradlew :session:testDebugUnitTest` (`EgressAllowlistTest`, `KeyVaultTest`, `EgressTest` with MockWebServer); on a device, `eval/m1/auth-01.sh` (M1-AUTH-01).
