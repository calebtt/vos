# eval — acceptance tests that run on a device

Test IDs come from TARGET-PLAN §6. Each runner pulls its results to the desktop: to `$VOS_EVAL_OUT` if set, else `$VOS_EVAL_ROOT/<date>/<test>-<time>/`, else under `eval/runs/` in the checkout (gitignored). On the workstation, `VOS_EVAL_ROOT` points at the eval runs directory on the data disk (§7). Nothing from a run is committed.

| Test | Runner | Device | What it installs |
|---|---|---|---|
| M1-AUTH-01 (#132) | [`m1/auth-01.sh`](m1/auth-01.sh) | emulator or Phone B (debug builds only, §5.3) | `com.vos.actuator`, `com.vos.session` (debug), their androidTest APKs, and the fixtures in `fixtures/auth`; removes the test APKs and fixtures afterwards unless `--keep-fixtures` |

`m1/auth-probe/` is the on-device probe (`AuthProbe`, a plain `Instrumentation` with no AndroidX), compiled into the androidTest APKs and the fixtures. It reaches a service three ways: through `PeerLink` with the protocol-version handshake, by a raw `transact()` (the guard in `onTransact`), and by `Stub.asInterface()` (in the same process, a direct call on the Stub, so the guard inside each method). The two `ok` cases also time 200 guarded calls and record p50/p95 in `results.tsv`. `fixtures/auth/` builds two fixture APKs: `wrongpkg` (same debug cert, wrong package) and `foreign` (a throwaway key made in `build/` at build time).
