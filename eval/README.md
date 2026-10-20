# eval — acceptance tests that run on a device

Test IDs come from TARGET-PLAN §6. Each runner pulls its results to the desktop, by default under `/media/caleb/data-disk/vos/eval/runs/<date>/` (§7); nothing from a run is committed.

| Test | Runner | Device | What it installs |
|---|---|---|---|
| M1-AUTH-01 (#132) | [`m1/auth-01.sh`](m1/auth-01.sh) | emulator or Phone B (debug builds only, §5.3) | `com.vos.actuator`, `com.vos.session` (debug), their androidTest APKs, and the fixtures in `fixtures/auth`; removes the test APKs and fixtures afterwards unless `--keep-fixtures` |

`m1/auth-probe/` is the on-device probe (`AuthProbe`, a plain `Instrumentation` with no AndroidX), compiled into the androidTest APKs and the fixtures. `fixtures/auth/` builds two fixture APKs: `wrongpkg` (same debug cert, wrong package) and `foreign` (a throwaway key made in `build/` at build time).
