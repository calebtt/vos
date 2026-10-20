# ci — static policy check (M0-02, #128)

`policy_check.py` inspects the **built** APKs (release and debug of both apps) and the repo. It runs as `./gradlew policyCheck`, which `./gradlew check` depends on, and in `.github/workflows/ci.yml`.

| Rule | How | List |
|---|---|---|
| Actuator: no `INTERNET` (nor `CALL_AUDIO_INTERCEPTION`) | `aapt2 dump xmltree` of the merged manifest | `actuator-denied-permissions.txt` |
| Session: no `RECORD_AUDIO`, a11y, NLS, voice interaction, camera, location, SMS/call | same | `session-denied-permissions.txt` |
| Session: no signature\|privileged permissions | each requested permission's `protectionLevel` in the framework manifest of the SDK's `android.jar` (fails on the `privileged` flag or legacy `signatureOrSystem`), plus a fixed denylist for names that manifest lacks | `privileged-permissions.txt` |
| Each app's Binder guard permission (`SESSION` defined by the actuator, `ACTUATOR` by the session) is defined and exactly `signature` (`0x2`, no added flags) | `<permission>` in the merged manifest | — |
| Session: hosts no accessibility / NLS / voice-interaction service | bind permission, intent action, meta-data | — |
| Only declared exported components, with the declared guard permission; system-bound services (accessibility, NLS, voice interaction, QS tiles) must require their system `BIND_*` permission | manifest; fails on extra, mismatched or stale entries. Debug APKs also allow the debug-only list, which fails the check if one of its components reaches a release APK | `exported-components-{actuator,session}.txt`, `exported-components-actuator-debug.txt` |
| Release APKs not debuggable | manifest | — |
| No Play services, Firebase, ML Kit (and Play Core) | Gradle dependency graph (`writePolicyDeps` → `build/policy/deps.txt`) + dex string tables + resource strings + zip entries + manifest. A dex string that only names such a package (OkHttp's Play-provider socket probe) is exempt by exact match, per app, with its reason | `forbidden-artifacts.txt`, `forbidden-exceptions.txt` |
| Actuator holds no HTTP/WebSocket library | dex | `forbidden-artifacts.txt` (`actuator-class:`) |
| No retired names | whole-token, case-insensitive, over code, manifests, resources, build scripts, CI config (`actuator/ session/ vtb-sdk/ ci/ .github/ gradle/ image/ fork/ eval/` + root build files) and the APK strings | `retired-names.txt` (the only file allowed to contain them) |
| Signing: release cert ≠ debug cert; actuator and session release share one signer | `apksigner verify --print-certs` | — |
| gitleaks clean on the repo and on the APK strings | `gitleaks dir` over a temp copy of tracked + untracked-not-ignored files, and over the dumped release APK strings (temp dir, never the repo); CI also runs `gitleaks git` over full history | — |

**Retired names.** Taken from the plan (the "Replaces" row and the §7 archive line name the old projects) plus those projects' old application ids. Docs (`docs/`, `research/`) are outside the scan scope because the plan names the old projects there. Add any other old name to `retired-names.txt`.

**ML Kit.** All of `com.google.mlkit` is denied for now (its artifacts pull Play services). S-23 compares a bundled ML Kit model with PP-OCR; if it picks ML Kit, the rule needs a plan change first.

**Signing choice.** Without a release key the release APKs build **unsigned** (`*-release-unsigned.apk`) and the cert comparison is skipped with a NOTE. CI generates a random **throwaway** release key outside the repo (`make-throwaway-release-key.sh`) and sets `VOS_REQUIRE_SIGNED_RELEASE=1`, so the release signing path and the cert checks run on every push. The real release key never enters CI (§5.4); locally it comes from `VOS_RELEASE_*` env vars or an ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

**gitleaks.** `fetch-gitleaks.sh` installs a pinned, SHA-256-verified release into `.tools/` (gitignored). `--gitleaks auto` (the Gradle default) skips with a NOTE if it is missing; CI sets `VOS_GITLEAKS=required`.

**Negative tests.** `tests/test_policy_check.py` feeds violating fixtures (synthetic xmltree dumps, dex files, APK zips, files with retired names, a runtime-generated token) to each rule and asserts it fails. Run: `python3 -I -m unittest discover -s ci/tests -t ci -v` or `./gradlew policyCheckTests`.
