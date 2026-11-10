"""eval/m1/lib.sh vos_install_apps (#341): session first, then the actuator, and both cross grants
read back; an app that lacks its grant is reinstalled once, and a grant still missing fails loudly.

No phone: the test redefines adb as a shell function. The fake keeps an install log and answers
`dumpsys package` like Android does for a signature permission defined by another app: the user is
granted it only if the definer was already installed when the user was last installed.
"""

import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

SCRIPT = r"""
set -euo pipefail
ROOT="$1"
source "$ROOT/eval/m1/lib.sh"
LOG="$2" MODE="$3"
pkg_of() { case "${1##*/}" in actuator-*) echo com.vos.actuator ;; session-*) echo com.vos.session ;; *) echo other ;; esac; }
# granted PKG PEER: PKG's last install came after PEER's first install, unless MODE says never.
granted() {
    [[ $MODE == never && $1 == com.vos.actuator ]] && return 1
    local last first
    last="$(grep -n -x "$1" "$LOG" | tail -1 | cut -d: -f1)"
    first="$(grep -n -x "$2" "$LOG" | head -1 | cut -d: -f1)"
    [[ -n $last && -n $first ]] && (( first < last ))
}
adb() {
    case "$1 ${2:-} ${3:-}" in
        "install -r -t") pkg_of "$4" >> "$LOG" ;;
        "shell dumpsys package")
            echo "Packages:"; echo "  Package [$4]"; echo "    install permissions:"
            if [[ $4 == com.vos.actuator ]]; then
                granted com.vos.actuator com.vos.session && echo "      com.vos.permission.ACTUATOR: granted=true"
            else
                granted com.vos.session com.vos.actuator && echo "      com.vos.permission.SESSION: granted=true"
            fi
            printf '      android.permission.INTERNET: granted=true\r\n' ;;
        *) echo "unexpected adb $*" >&2; return 9 ;;
    esac
}
rc=0
vos_install_apps "$ROOT/eval/extra-test.apk" || rc=$?
echo "rc=$rc"
"""


class InstallAppsTest(unittest.TestCase):
    def run_install(self, already=(), mode="model"):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "installs"
            log.write_text("".join(p + "\n" for p in already))
            p = subprocess.run(["bash", "-c", SCRIPT, "self-test", str(ROOT), str(log), mode],
                               capture_output=True, text=True, timeout=30)
            self.assertEqual(p.returncode, 0, p.stderr)
            installs = log.read_text().split()[len(already):]
            return p.stdout.strip().splitlines()[-1], installs, p.stderr

    def test_fresh_device_ends_with_both_grants(self):
        rc, installs, err = self.run_install()
        self.assertEqual(rc, "rc=0", err)
        self.assertEqual(installs[:2], ["com.vos.session", "com.vos.actuator"], "session first, then the actuator")
        # Whichever app goes first has no definer yet: it is reinstalled once, and no more.
        self.assertEqual(installs[2:], ["com.vos.session", "other"])
        self.assertIn("com.vos.session lacks com.vos.permission.SESSION", err)

    def test_upgrade_reinstalls_nothing_extra(self):
        rc, installs, err = self.run_install(already=("com.vos.actuator", "com.vos.session"))
        self.assertEqual(rc, "rc=0", err)
        self.assertEqual(installs, ["com.vos.session", "com.vos.actuator", "other"])
        self.assertEqual(err, "")

    def test_a_grant_that_never_comes_fails_after_one_reinstall(self):
        rc, installs, err = self.run_install(already=("com.vos.actuator", "com.vos.session"), mode="never")
        self.assertEqual(rc, "rc=1", "a missing grant must fail the install, not pass silently")
        self.assertEqual(installs, ["com.vos.session", "com.vos.actuator", "com.vos.actuator"],
                         "exactly one reinstall of the app that lacks it; no extra APKs after a failure")
        self.assertIn("FAIL: com.vos.actuator still lacks com.vos.permission.ACTUATOR", err)

    def test_lib_self_test_passes(self):
        p = subprocess.run(["bash", "-c", 'source "$1/eval/m1/lib.sh"; vos_lib_self_test', "t", str(ROOT)],
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(p.returncode, 0, p.stderr)


if __name__ == "__main__":
    unittest.main()
