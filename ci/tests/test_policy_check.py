"""Negative tests for ci/policy_check.py: every rule must FAIL on a violating fixture.

Fixtures are synthetic aapt2 xmltree dumps, dex files and APK zips built in a temp dir at test
time. Retired names and secrets are never written into this file: they are read from
ci/retired-names.txt or generated at runtime.
"""

import os
import secrets
import string
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

import policy_check as pc

CI = Path(__file__).resolve().parents[1]
NS = "http://schemas.android.com/apk/res/android:"


def xmltree(package, permissions=(), components=(), app_attrs=(), defines=()):
    """A dump in aapt2 `dump xmltree` layout. components: (kind, name, exported, permission, actions, meta);
    defines: (name, protectionLevel int or None) for <permission> elements."""
    lines = [
        "N: android=http://schemas.android.com/apk/res/android (line=2)",
        "  E: manifest (line=2)",
        f'    A: package="{package}" (Raw: "{package}")',
    ]
    for name, level in defines:
        lines += ["      E: permission (line=4)", f'        A: {NS}name(0x01010003)="{name}" (Raw: "{name}")']
        if level is not None:
            lines.append(f"        A: {NS}protectionLevel(0x01010009)=0x{level:08x}")
    for p in permissions:
        lines += ["      E: uses-permission (line=5)", f'        A: {NS}name(0x01010003)="{p}" (Raw: "{p}")']
    lines.append("      E: application (line=9)")
    for name, value in app_attrs:
        lines.append(f"        A: {NS}{name}(0x0101000f)={value}")
    for kind, name, exported, perm, actions, meta in components:
        lines += [f"          E: {kind} (line=10)", f'            A: {NS}name(0x01010003)="{name}" (Raw: "{name}")']
        if perm:
            lines.append(f'            A: {NS}permission(0x01010006)="{perm}" (Raw: "{perm}")')
        if exported is not None:
            lines.append(f"            A: {NS}exported(0x01010010)={'true' if exported else 'false'}")
        if actions:
            lines.append("              E: intent-filter (line=12)")
            for a in actions:
                lines += ["                  E: action (line=13)",
                          f'                    A: {NS}name(0x01010003)="{a}" (Raw: "{a}")']
        for m in meta:
            lines += ["              E: meta-data (line=15)", f'                A: {NS}name(0x01010003)="{m}" (Raw: "{m}")']
    return "\n".join(lines) + "\n"


def facts(*args, **kwargs):
    return pc.manifest_facts(pc.parse_xmltree(xmltree(*args, **kwargs)))


def make_dex(strings_):
    """Minimal dex: header + string_ids + string_data (enough for policy_check.dex_strings)."""
    header = bytearray(0x70)
    header[0:8] = b"dex\n035\x00"
    ids_off = 0x70
    data_off = ids_off + 4 * len(strings_)
    ids, data = bytearray(), bytearray()
    for s in strings_:
        ids += struct.pack("<I", data_off + len(data))
        raw = s.encode("utf-8")
        assert len(raw) < 0x80
        data += bytes([len(raw)]) + raw + b"\x00"
    struct.pack_into("<II", header, 0x38, len(strings_), ids_off)
    return bytes(header + ids + data)


ACTUATOR_OK = dict(
    package="com.vos.actuator",
    defines=[("com.vos.permission.SESSION", 0x2)],
    permissions=["android.permission.RECORD_AUDIO", "com.vos.permission.ACTUATOR"],
    components=[
        ("service", "com.vos.actuator.ActuatorService", True, "com.vos.permission.SESSION", [], []),
        ("service", "com.vos.actuator.HandsService", True, "android.permission.BIND_ACCESSIBILITY_SERVICE",
         ["android.accessibilityservice.AccessibilityService"], ["android.accessibilityservice"]),
        ("service", "com.vos.actuator.NotificationsService", True,
         "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
         ["android.service.notification.NotificationListenerService"], []),
        ("service", "com.vos.actuator.SummonTile", True, "android.permission.BIND_QUICK_SETTINGS_TILE",
         ["android.service.quicksettings.action.QS_TILE"], []),
    ],
)
SESSION_OK = dict(
    package="com.vos.session",
    defines=[("com.vos.permission.ACTUATOR", 0x2)],
    permissions=["android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS", "com.vos.permission.SESSION"],
    components=[
        ("activity", "com.vos.session.MainActivity", True, None, ["android.intent.action.MAIN"], []),
        ("service", "com.vos.session.SessionService", True, "com.vos.permission.ACTUATOR", [], []),
    ],
)


# Protection levels as the framework manifest in android.jar defines them (android-35).
PLATFORM_LEVELS = {
    "android.permission.INTERNET": 0x0,
    "android.permission.POST_NOTIFICATIONS": 0x1,
    "android.permission.RECORD_AUDIO": 0x1001,
    "android.permission.CAPTURE_AUDIO_HOTWORD": 0x04000012,
    "android.permission.ACCESS_ULTRASOUND": 0x12,
    "android.permission.BIND_ACCESSIBILITY_SERVICE": 0x2,
}


class PermissionRules(unittest.TestCase):
    def setUp(self):
        self.actuator_denied = pc.read_list(CI / "actuator-denied-permissions.txt")
        self.session_denied = (pc.read_list(CI / "session-denied-permissions.txt")
                               + pc.read_list(CI / "privileged-permissions.txt"))

    def test_clean_fixtures_pass(self):
        self.assertEqual([], pc.check_denied_permissions("actuator", facts(**ACTUATOR_OK), self.actuator_denied))
        self.assertEqual([], pc.check_denied_permissions("session", facts(**SESSION_OK), self.session_denied))
        self.assertEqual([], pc.check_no_sensitive_components("session", facts(**SESSION_OK)))

    def test_actuator_with_internet_fails(self):
        f = facts("com.vos.actuator", ["android.permission.RECORD_AUDIO", "android.permission.INTERNET"])
        fails = pc.check_denied_permissions("actuator", f, self.actuator_denied)
        self.assertEqual(1, len(fails))
        self.assertIn("android.permission.INTERNET", fails[0])

    def test_session_with_record_audio_fails(self):
        f = facts("com.vos.session", ["android.permission.INTERNET", "android.permission.RECORD_AUDIO"])
        self.assertTrue(pc.check_denied_permissions("session", f, self.session_denied))

    def test_session_with_privileged_permission_fails(self):
        for perm in ("android.permission.MODIFY_AUDIO_ROUTING", "android.permission.CAPTURE_AUDIO_OUTPUT",
                     "android.permission.WRITE_SECURE_SETTINGS"):
            f = facts("com.vos.session", ["android.permission.INTERNET", perm])
            self.assertTrue(pc.check_denied_permissions("session", f, self.session_denied), perm)

    def test_own_guard_permissions_are_signature(self):
        self.assertEqual([], pc.check_own_permission("actuator", facts(**ACTUATOR_OK)))
        self.assertEqual([], pc.check_own_permission("session", facts(**SESSION_OK)))

    def test_own_guard_permission_downgraded_or_missing_fails(self):
        # normal, dangerous, signature|privileged, signature|knownSigner, no level, not defined at all
        for defines in ([("com.vos.permission.ACTUATOR", 0x0)], [("com.vos.permission.ACTUATOR", 0x1)],
                        [("com.vos.permission.ACTUATOR", 0x12)], [("com.vos.permission.ACTUATOR", 0x08000002)],
                        [("com.vos.permission.ACTUATOR", None)], []):
            f = facts(**{**SESSION_OK, "defines": defines})
            fails = pc.check_own_permission("session", f)
            self.assertEqual(1, len(fails), defines)
            self.assertIn("com.vos.permission.ACTUATOR", fails[0])

    def test_session_with_unlisted_privileged_permission_fails(self):
        self.assertEqual([], pc.check_no_privileged_permissions("session", facts(**SESSION_OK), PLATFORM_LEVELS))
        for perm in ("android.permission.CAPTURE_AUDIO_HOTWORD", "android.permission.ACCESS_ULTRASOUND"):
            self.assertNotIn(perm, self.session_denied)  # caught by its protection flags, not the fixed list
            f = facts("com.vos.session", ["android.permission.INTERNET", perm])
            fails = pc.check_no_privileged_permissions("session", f, PLATFORM_LEVELS)
            self.assertEqual(1, len(fails), perm)
            self.assertIn(perm, fails[0])
        self.assertTrue(pc.is_signature_privileged(0x3))  # legacy signatureOrSystem
        self.assertFalse(pc.is_signature_privileged(0x2))

    def test_platform_levels_from_the_sdk(self):
        try:
            sdk = pc.find_sdk(CI.parent)
            aapt2 = pc.build_tool(sdk, "aapt2")
            _, levels = pc.platform_permission_levels(aapt2, sdk)
        except SystemExit as e:
            self.skipTest(f"no Android SDK: {e}")
        self.assertTrue(pc.is_signature_privileged(levels["android.permission.CAPTURE_AUDIO_HOTWORD"]))
        self.assertTrue(pc.is_signature_privileged(levels["android.permission.MODIFY_AUDIO_ROUTING"]))
        self.assertFalse(pc.is_signature_privileged(levels["android.permission.INTERNET"]))
        self.assertFalse(pc.is_signature_privileged(levels["android.permission.POST_NOTIFICATIONS"]))

    def test_session_with_a11y_or_nls_permission_fails(self):
        for perm in ("android.permission.BIND_ACCESSIBILITY_SERVICE",
                     "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"):
            f = facts("com.vos.session", [perm])
            self.assertTrue(pc.check_denied_permissions("session", f, self.session_denied), perm)

    def test_session_hosting_accessibility_service_fails(self):
        f = facts("com.vos.session", components=[(
            "service", "com.vos.session.Hands", False, "android.permission.BIND_ACCESSIBILITY_SERVICE",
            ["android.accessibilityservice.AccessibilityService"], ["android.accessibilityservice"])])
        fails = pc.check_no_sensitive_components("session", f)
        self.assertEqual(1, len(fails))
        self.assertIn("com.vos.session.Hands", fails[0])

    def test_session_hosting_notification_listener_fails(self):
        f = facts("com.vos.session", components=[(
            "service", ".Nls", None, None, ["android.service.notification.NotificationListenerService"], [])])
        self.assertTrue(pc.check_no_sensitive_components("session", f))

    def test_wrong_package_fails(self):
        self.assertTrue(pc.check_package("session", facts("com.example.session")))

    def test_debuggable_release_fails(self):
        f = facts("com.vos.session", app_attrs=[("debuggable", "true")])
        self.assertTrue(pc.check_not_debuggable("session", f))
        self.assertEqual([], pc.check_not_debuggable("session", facts(**SESSION_OK)))


class ExportedRules(unittest.TestCase):
    def setUp(self):
        self.session_allow = pc.read_exported_allowlist(CI / "exported-components-session.txt")
        self.actuator_allow = pc.read_exported_allowlist(CI / "exported-components-actuator.txt")

    def test_declared_components_pass(self):
        self.assertEqual([], pc.check_exported("session", facts(**SESSION_OK), self.session_allow))
        self.assertEqual([], pc.check_exported("actuator", facts(**ACTUATOR_OK), self.actuator_allow))

    def test_undeclared_exported_activity_fails(self):
        f = facts(**dict(SESSION_OK, components=SESSION_OK["components"] + [
            ("activity", "com.vos.session.DebugActivity", True, None, [], [])]))
        fails = pc.check_exported("session", f, self.session_allow)
        self.assertEqual(1, len(fails))
        self.assertIn("DebugActivity", fails[0])

    def test_implicitly_exported_component_fails(self):
        f = facts(**dict(ACTUATOR_OK, components=ACTUATOR_OK["components"] + [
            ("receiver", ".Boot", None, None, ["android.intent.action.BOOT_COMPLETED"], [])]))
        self.assertTrue(pc.check_exported("actuator", f, self.actuator_allow))

    def test_permission_mismatch_fails(self):
        f = facts("com.vos.actuator", components=[("service", "com.vos.actuator.ActuatorService", True, None, [], [])])
        fails = pc.check_exported("actuator", f, self.actuator_allow)
        self.assertTrue(any("has permission None" in x for x in fails))

    def test_exported_a11y_service_without_bind_permission_fails(self):
        comps = [c for c in ACTUATOR_OK["components"] if c[1] != "com.vos.actuator.HandsService"]
        comps.append(("service", "com.vos.actuator.HandsService", True, None,
                      ["android.accessibilityservice.AccessibilityService"], ["android.accessibilityservice"]))
        fails = pc.check_exported("actuator", facts(**dict(ACTUATOR_OK, components=comps)), self.actuator_allow)
        self.assertTrue(any("not guarded by android.permission.BIND_ACCESSIBILITY_SERVICE" in x for x in fails))

    def test_exported_nls_with_wrong_permission_fails(self):
        comps = [c for c in ACTUATOR_OK["components"] if c[1] != "com.vos.actuator.NotificationsService"]
        comps.append(("service", "com.vos.actuator.NotificationsService", True, "com.vos.permission.SESSION",
                      ["android.service.notification.NotificationListenerService"], []))
        fails = pc.check_exported("actuator", facts(**dict(ACTUATOR_OK, components=comps)), self.actuator_allow)
        self.assertTrue(any("not guarded by android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" in x for x in fails))

    def test_unlisted_guarded_a11y_service_still_fails(self):
        f = facts(**dict(ACTUATOR_OK, components=ACTUATOR_OK["components"] + [
            ("service", "com.vos.actuator.OtherHands", True, "android.permission.BIND_ACCESSIBILITY_SERVICE",
             ["android.accessibilityservice.AccessibilityService"], [])]))
        self.assertTrue(any("OtherHands is not in the allowlist" in x
                            for x in pc.check_exported("actuator", f, self.actuator_allow)))

    def test_qs_tile_without_its_bind_permission_fails(self):
        comps = [c for c in ACTUATOR_OK["components"] if c[1] != "com.vos.actuator.SummonTile"]
        comps.append(("service", "com.vos.actuator.SummonTile", True, None,
                      ["android.service.quicksettings.action.QS_TILE"], []))
        fails = pc.check_exported("actuator", facts(**dict(ACTUATOR_OK, components=comps)), self.actuator_allow)
        self.assertTrue(any("not guarded by android.permission.BIND_QUICK_SETTINGS_TILE" in x for x in fails))

    def test_debug_only_components_pass_in_debug_and_fail_in_release(self):
        debug_activity = ("activity", "com.vos.actuator.DebugSummonActivity", True, "android.permission.DUMP", [], [])
        f = facts(**dict(ACTUATOR_OK, components=ACTUATOR_OK["components"] + [debug_activity]))
        self.assertEqual([], pc.check_exported("actuator", f, pc.exported_allowlist(CI, "actuator", "debug")))
        release = pc.check_exported("actuator", f, pc.exported_allowlist(CI, "actuator", "release"))
        self.assertTrue(any("DebugSummonActivity is not in the allowlist" in x for x in release))
        # The debug list does not loosen the guard: the same activity without DUMP fails in debug.
        unguarded = ("activity", "com.vos.actuator.DebugSummonActivity", True, None, [], [])
        f2 = facts(**dict(ACTUATOR_OK, components=ACTUATOR_OK["components"] + [unguarded]))
        self.assertTrue(pc.check_exported("actuator", f2, pc.exported_allowlist(CI, "actuator", "debug")))

    def test_stale_allowlist_fails(self):
        f = facts("com.vos.actuator", components=[("service", "com.vos.actuator.ActuatorService", False,
                                                   "com.vos.permission.SESSION", [], [])])
        self.assertTrue(any("stale" in x for x in pc.check_exported("actuator", f, self.actuator_allow)))


class GoogleArtifactRules(unittest.TestCase):
    def setUp(self):
        self.groups, self.classes, self.actuator_classes = pc.read_forbidden(CI / "forbidden-artifacts.txt")

    def test_dependency_graph_rejects_play_firebase_mlkit(self):
        for coord in ("com.google.android.gms:play-services-base:18.5.0",
                      "com.google.firebase:firebase-messaging:24.0.0",
                      "com.google.mlkit:text-recognition:16.0.1",
                      "com.google.android.gms:play-services-mlkit-text-recognition:19.0.1"):
            self.assertTrue(pc.check_deps("session", [coord], self.groups), coord)
        self.assertEqual([], pc.check_deps("session", ["junit:junit:4.13.2", "com.google.guava:guava:33.0"],
                                           self.groups))

    def test_dex_parser_round_trip(self):
        self.assertEqual(["La;", "hello"], pc.dex_strings(make_dex(["La;", "hello"])))

    def test_apk_with_gms_class_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "bad.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", make_dex(["Lcom/vos/session/MainActivity;",
                                                    "Lcom/google/android/gms/common/GoogleApiAvailability;"]))
            dex = pc.apk_dex_strings(apk)
            fails = pc.check_forbidden_in_apk("session", dex, [], pc.apk_entries(apk),
                                              facts(**SESSION_OK), self.classes)
            self.assertTrue(any("com.google.android.gms" in x for x in fails))

    def test_apk_with_firebase_resources_and_version_file_fails(self):
        entries = ["classes.dex", "META-INF/com.google.firebase_firebase-common.version"]
        fails = pc.check_forbidden_in_apk("session", [], ["google_app_id"], entries, facts(**SESSION_OK),
                                          self.classes)
        self.assertTrue(any("google_app_id" in x for x in fails))
        self.assertTrue(any("com.google.firebase" in x for x in fails))

    def test_manifest_gms_meta_data_fails(self):
        f = facts("com.vos.session", components=[
            ("service", "com.google.firebase.messaging.FirebaseMessagingService", False, None, [], [])])
        self.assertTrue(pc.check_forbidden_in_apk("session", [], [], [], f, self.classes))

    def test_actuator_http_library_fails(self):
        dex = ["Lokhttp3/OkHttpClient;"]
        self.assertTrue(pc.check_forbidden_in_apk("actuator", dex, [], [], facts(**ACTUATOR_OK),
                                                  self.classes + self.actuator_classes))

    def test_documented_dex_exception_is_exact_and_per_app(self):
        exceptions = pc.read_exceptions(CI / "forbidden-exceptions.txt")
        allowed = exceptions["session"]
        probe = "com.google.android.gms.org.conscrypt"
        self.assertIn(probe, allowed)
        self.assertEqual([], pc.check_forbidden_in_apk("session", [probe], [], [], facts(**SESSION_OK),
                                                       self.classes, allowed))
        # Not a prefix: a class under it, or any other gms string, still fails.
        for other in ("com.google.android.gms.org.conscrypt.OpenSSLProvider",
                      "Lcom/google/android/gms/org/conscrypt/Conscrypt;", "com.google.android.gms.common"):
            self.assertTrue(pc.check_forbidden_in_apk("session", [probe, other], [], [], facts(**SESSION_OK),
                                                      self.classes, allowed), other)
        # Not for the other app.
        self.assertTrue(pc.check_forbidden_in_apk("actuator", [probe], [], [], facts(**ACTUATOR_OK),
                                                  self.classes, exceptions.get("actuator", frozenset())))
        # Not for resources, zip entries or manifest names, each on its own.
        self.assertTrue(pc.check_forbidden_in_apk("session", [], [probe], [], facts(**SESSION_OK),
                                                  self.classes, allowed))
        self.assertTrue(pc.check_forbidden_in_apk("session", [], [], [probe], facts(**SESSION_OK),
                                                  self.classes, allowed))
        manifest = facts("com.vos.session", components=[("service", probe + ".Svc", False, None, [], [])])
        self.assertTrue(pc.check_forbidden_in_apk("session", [], [], [], manifest, self.classes, allowed))

    def test_exceptions_file_rejects_malformed_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "x.txt"
            bad.write_text("nobody com.google.android.gms.x\n")
            with self.assertRaises(ValueError):
                pc.read_exceptions(bad)

    def test_clean_apk_passes(self):
        dex = ["Lcom/vos/session/MainActivity;", "Landroid/app/Activity;"]
        self.assertEqual([], pc.check_forbidden_in_apk("session", dex, ["VOS"], ["classes.dex"],
                                                       facts(**SESSION_OK), self.classes))


class RetiredNames(unittest.TestCase):
    def setUp(self):
        self.names = pc.read_list(CI / "retired-names.txt")
        self.assertTrue(self.names, "ci/retired-names.txt must list at least one name")

    def test_every_listed_name_is_caught(self):
        for name in self.names:
            text = f"package x;\n// moved from {name.upper()} here\n"
            fails = pc.check_retired({"session/src/X.java": text}, self.names)
            self.assertTrue(fails, name)
            self.assertIn("session/src/X.java:2", fails[0])

    def test_whole_token_match_only(self):
        self.assertEqual([], pc.check_retired({"a": "xfoobar foobarx"}, ["foobar"]))
        self.assertTrue(pc.check_retired({"a": "use foobar.thing"}, ["foobar"]))

    def test_scan_covers_code_build_and_ci_but_not_the_list_or_docs(self):
        with tempfile.TemporaryDirectory() as tmp:
            name = self.names[0]
            files = {
                "session/src/main/java/A.java": f"// {name}\n",
                ".github/workflows/ci.yml": f"name: {name}\n",
                "settings.gradle.kts": f'rootProject.name = "{name}"\n',
                "ci/retired-names.txt": f"{name}\n",
                "docs/TARGET-PLAN.md": f"Replaces {name}\n",
            }
            for rel, text in files.items():
                p = Path(tmp) / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text)
            texts = pc.retired_scan_texts(tmp, sorted(files))
            self.assertEqual({"session/src/main/java/A.java", ".github/workflows/ci.yml", "settings.gradle.kts"},
                             set(texts))
            self.assertEqual(3, len(pc.check_retired(texts, self.names)))


class Signing(unittest.TestCase):
    def test_release_sharing_debug_cert_fails(self):
        d = {(a, t): "ab" * 32 for a in pc.APPS for t in ("debug", "release")}
        fails, _ = pc.check_signers(d, require_signed_release=False)
        self.assertTrue(any("share signing cert" in x for x in fails))

    def test_split_release_signers_fail(self):
        d = {("actuator", "debug"): "aa" * 32, ("session", "debug"): "aa" * 32,
             ("actuator", "release"): "bb" * 32, ("session", "release"): "cc" * 32}
        fails, _ = pc.check_signers(d, require_signed_release=False)
        self.assertTrue(any("certs differ" in x for x in fails))

    def test_unsigned_release_fails_only_when_required(self):
        d = {("actuator", "debug"): "aa" * 32, ("session", "debug"): "aa" * 32,
             ("actuator", "release"): None, ("session", "release"): None}
        self.assertEqual([], pc.check_signers(d, require_signed_release=False)[0])
        self.assertTrue(pc.check_signers(d, require_signed_release=True)[0])

    def test_distinct_release_cert_passes(self):
        d = {("actuator", "debug"): "aa" * 32, ("session", "debug"): "aa" * 32,
             ("actuator", "release"): "bb" * 32, ("session", "release"): "bb" * 32}
        self.assertEqual([], pc.check_signers(d, require_signed_release=True)[0])


GITLEAKS = pc.find_gitleaks(CI.parent)


@unittest.skipIf(GITLEAKS is None and os.environ.get("VOS_GITLEAKS") != "required",
                 "gitleaks not installed (ci/fetch-gitleaks.sh); CI sets VOS_GITLEAKS=required")
class Gitleaks(unittest.TestCase):
    def test_gitleaks_present_when_required(self):
        self.assertIsNotNone(GITLEAKS)

    def test_planted_token_is_found(self):
        alphabet = string.ascii_letters + string.digits
        token = "ghp_" + "".join(secrets.choice(alphabet) for _ in range(36))
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "dex-strings.txt").write_text(f"Lcom/vos/X;\nconst token = \"{token}\"\n")
            clean, _ = pc.run_gitleaks_dir(GITLEAKS, tmp)
            self.assertFalse(clean)

    def test_clean_dir_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "dex-strings.txt").write_text("Lcom/vos/session/MainActivity;\n")
            clean, out = pc.run_gitleaks_dir(GITLEAKS, tmp)
            self.assertTrue(clean, out)


if __name__ == "__main__":
    unittest.main()
