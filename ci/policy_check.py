#!/usr/bin/env python3
"""Static policy check of the built VOS APKs (TARGET-PLAN M0-02).

Inspects the merged manifests (aapt2 dump xmltree), the dex string tables, the resource strings
and the zip entries of the actuator and session APKs, the Gradle dependency lists written by
`writePolicyDeps`, the repo's code/build/CI files, and runs gitleaks on the repo and on the APK
strings. Exit status is non-zero on any FAIL. Rules and lists are documented in ci/README.md.

Usage: python3 -I ci/policy_check.py --root . [--gitleaks auto|required|off]
                                      [--require-signed-release]
"""

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ANDROID_NS = "http://schemas.android.com/apk/res/android:"
APPS = ("actuator", "session")
PACKAGES = {"actuator": "com.vos.actuator", "session": "com.vos.session"}
# The permission each app defines to guard its Binder entry; it must stay exactly `signature` (§5.3).
OWN_PERMISSIONS = {"actuator": "com.vos.permission.SESSION", "session": "com.vos.permission.ACTUATOR"}
PROTECTION_SIGNATURE = 0x2
PROTECTION_SIGNATURE_OR_SYSTEM = 0x3
PROTECTION_FLAG_PRIVILEGED = 0x10

# Components bound by the system for accessibility, notification access and voice interaction.
SENSITIVE_BIND_PERMISSIONS = {
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.permission.BIND_VOICE_INTERACTION",
}
SENSITIVE_ACTIONS = {
    "android.accessibilityservice.AccessibilityService",
    "android.service.notification.NotificationListenerService",
    "android.service.voice.VoiceInteractionService",
}
SENSITIVE_META_DATA = {"android.accessibilityservice", "android.voice_interaction"}
# The system-only permission an exported system-bound service must require, by its intent action.
REQUIRED_BIND_PERMISSION = {
    "android.accessibilityservice.AccessibilityService": "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.service.notification.NotificationListenerService": "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.service.voice.VoiceInteractionService": "android.permission.BIND_VOICE_INTERACTION",
    "android.service.quicksettings.action.QS_TILE": "android.permission.BIND_QUICK_SETTINGS_TILE",
    # VOS offers no speech recognition to apps: the recognition service the assistant must name is the system's only.
    "android.speech.RecognitionService": "android.permission.BIND_VOICE_INTERACTION",
}
# The same, for system-bound services with no intent action of their own, by class name.
REQUIRED_BIND_PERMISSION_BY_NAME = {
    "com.vos.actuator.VosAssistSessionService": "android.permission.BIND_VOICE_INTERACTION",
}

# Where retired names must not appear: code, manifests, resources, build scripts, CI config.
RETIRED_SCAN_DIRS = ("actuator", "session", "vtb-sdk", "ipc", "ci", ".github", "gradle", "image", "fork", "eval")
RETIRED_SCAN_ROOT_FILES = ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat")
RETIRED_NAMES_FILE = "ci/retired-names.txt"


# ---------------------------------------------------------------------------------------------
# List files
# ---------------------------------------------------------------------------------------------

def read_list(path):
    """Non-comment, non-blank lines of a ci/ list file."""
    out = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line)
    return out


def read_forbidden(path):
    groups, classes, actuator_classes = [], [], []
    for line in read_list(path):
        kind, _, value = line.partition(":")
        {"group": groups, "class": classes, "actuator-class": actuator_classes}[kind].append(value.strip())
    return groups, classes, actuator_classes


def read_exceptions(path):
    """{app: {exact dex string}} from forbidden-exceptions.txt."""
    out = {}
    for line in read_list(path):
        app, _, value = line.partition(" ")
        if app not in APPS or not value.strip() or " " in value.strip():
            raise ValueError(f"bad line in {path}: {line!r}")
        out.setdefault(app, set()).add(value.strip())
    return out


def read_exported_allowlist(path):
    """{(kind, name): permission or None}."""
    out = {}
    for line in read_list(path):
        kind, name, perm = line.split()
        out[(kind, name)] = None if perm == "-" else perm
    return out


def exported_allowlist(ci, app, build_type):
    """The app's exported-component allowlist; debug APKs add exported-components-<app>-debug.txt."""
    allow = read_exported_allowlist(Path(ci) / f"exported-components-{app}.txt")
    extra = Path(ci) / f"exported-components-{app}-debug.txt"
    if build_type == "debug" and extra.is_file():
        allow.update(read_exported_allowlist(extra))
    return allow


# ---------------------------------------------------------------------------------------------
# Manifest: parse `aapt2 dump xmltree --file AndroidManifest.xml`
# ---------------------------------------------------------------------------------------------

class Node:
    def __init__(self, tag, depth):
        self.tag, self.depth, self.attrs, self.children = tag, depth, {}, []

    def find_all(self, tag):
        for child in self.children:
            if child.tag == tag:
                yield child
            yield from child.find_all(tag)


_ATTR_RE = re.compile(r'^A: (?P<name>[^=(]+?)(?:\(0x[0-9a-f]+\))?=(?P<value>.*)$')


def _attr_value(raw):
    raw = raw.strip()
    if raw.startswith('"'):
        end = raw.find('"', 1)
        return raw[1:end] if end > 0 else raw[1:]
    return raw.split(" ", 1)[0]


def parse_xmltree(text):
    """Parse aapt2's indented xmltree dump into a Node tree (root tag '#document')."""
    root = Node("#document", -1)
    stack = [root]
    for line in text.splitlines():
        stripped = line.lstrip(" ")
        indent = len(line) - len(stripped)
        if stripped.startswith("E: "):
            tag = stripped[3:].split(" ", 1)[0]
            node = Node(tag, indent)
            while stack[-1].depth >= indent:
                stack.pop()
            stack[-1].children.append(node)
            stack.append(node)
        elif stripped.startswith("A: "):
            m = _ATTR_RE.match(stripped)
            if not m:
                continue
            name = m.group("name").strip()
            if name.startswith(ANDROID_NS):
                name = name[len(ANDROID_NS):]
            while stack[-1].depth >= indent:
                stack.pop()
            stack[-1].attrs[name] = _attr_value(m.group("value"))
    return root


def _truthy(value):
    return value is not None and value.lower() in ("true", "0xffffffff", "-1")


def _protection_level(value):
    """aapt2 prints a compiled protectionLevel as hex (signature is 0x00000002); None if absent or unreadable."""
    if value is None:
        return None
    try:
        return int(value, 0)
    except ValueError:
        return None


def declared_permissions(root):
    """{name: protectionLevel int or None} for every <permission> a manifest defines."""
    manifest = next(root.find_all("manifest"), None)
    if manifest is None:
        raise ValueError("no <manifest> element in xmltree dump")
    return {n.attrs.get("name", ""): _protection_level(n.attrs.get("protectionLevel"))
            for n in manifest.find_all("permission")}


def manifest_facts(root):
    manifest = next(root.find_all("manifest"), None)
    if manifest is None:
        raise ValueError("no <manifest> element in xmltree dump")
    package = manifest.attrs.get("package", "")
    uses = sorted({n.attrs.get("name", "") for t in ("uses-permission", "uses-permission-sdk-23")
                   for n in manifest.find_all(t)})
    app = next(manifest.find_all("application"), None)
    components = []
    meta = []
    if app is not None:
        meta = [m.attrs.get("name", "") for m in app.find_all("meta-data")]
        for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
            for c in app.find_all(kind):
                name = c.attrs.get("name", "")
                if name.startswith("."):
                    name = package + name
                filters = list(c.find_all("intent-filter"))
                actions = sorted({a.attrs.get("name", "") for f in filters for a in f.find_all("action")})
                exported_attr = c.attrs.get("exported")
                if exported_attr is not None:
                    exported = _truthy(exported_attr)
                else:
                    # Conservative: an intent filter without an explicit flag counts as exported.
                    exported = bool(filters) and kind != "provider"
                components.append({
                    "kind": "activity" if kind == "activity-alias" else kind,
                    "name": name,
                    "exported": exported,
                    "permission": c.attrs.get("permission"),
                    "actions": actions,
                    "meta": [m.attrs.get("name", "") for m in c.find_all("meta-data")],
                })
    return {
        "package": package,
        "uses_permissions": uses,
        "declared_permissions": declared_permissions(root),
        "components": components,
        "app_meta": meta,
        "debuggable": app is not None and _truthy(app.attrs.get("debuggable")),
    }


# ---------------------------------------------------------------------------------------------
# Rules on manifest facts (pure functions; each returns a list of failure strings)
# ---------------------------------------------------------------------------------------------

def check_package(app, facts):
    want = PACKAGES[app]
    return [] if facts["package"] == want else [f"{app}: package is {facts['package']!r}, want {want!r}"]


def check_denied_permissions(app, facts, denied):
    denied = set(denied)
    return [f"{app}: requests denied permission {p}" for p in facts["uses_permissions"] if p in denied]


def check_own_permission(app, facts):
    """The app's Binder guard permission must be defined, and be plain `signature` with no extra flags."""
    name = OWN_PERMISSIONS[app]
    declared = facts["declared_permissions"]
    if name not in declared:
        return [f"{app}: does not define {name}"]
    level = declared[name]
    if level != PROTECTION_SIGNATURE:
        shown = "missing" if level is None else f"0x{level:08x}"
        return [f"{app}: {name} has protectionLevel {shown}, want signature (0x{PROTECTION_SIGNATURE:08x})"]
    return []


def is_signature_privileged(level):
    return (level & 0xF) == PROTECTION_SIGNATURE_OR_SYSTEM or bool(level & PROTECTION_FLAG_PRIVILEGED)


def check_no_privileged_permissions(app, facts, platform_levels):
    """Fail on any requested platform permission whose protection level is signature|privileged."""
    fails = []
    for p in facts["uses_permissions"]:
        level = platform_levels.get(p)
        if level is not None and is_signature_privileged(level):
            fails.append(f"{app}: requests signature|privileged permission {p} (protectionLevel 0x{level:08x})")
    return fails


def check_no_sensitive_components(app, facts):
    """The session must not host accessibility, notification-listener or voice-interaction services."""
    fails = []
    for c in facts["components"]:
        why = []
        if c["permission"] in SENSITIVE_BIND_PERMISSIONS:
            why.append(f"permission {c['permission']}")
        why += [f"action {a}" for a in c["actions"] if a in SENSITIVE_ACTIONS]
        why += [f"meta-data {m}" for m in c["meta"] if m in SENSITIVE_META_DATA]
        if why:
            fails.append(f"{app}: component {c['name']} is a sensitive service ({', '.join(why)})")
    return fails


def check_exported(app, facts, allowlist):
    fails = []
    seen = set()
    for c in facts["components"]:
        if not c["exported"]:
            continue
        for action in c["actions"]:
            need = REQUIRED_BIND_PERMISSION.get(action)
            if need and c["permission"] != need:
                fails.append(f"{app}: exported {c['kind']} {c['name']} handles {action} but is not guarded by {need} "
                             f"(has {c['permission']!r})")
        need = REQUIRED_BIND_PERMISSION_BY_NAME.get(c["name"])
        if need and c["permission"] != need:
            fails.append(f"{app}: exported {c['kind']} {c['name']} is bound by the system but is not guarded by {need} "
                         f"(has {c['permission']!r})")
        key = (c["kind"], c["name"])
        seen.add(key)
        if key not in allowlist:
            fails.append(f"{app}: exported {c['kind']} {c['name']} is not in the allowlist")
        elif allowlist[key] != c["permission"]:
            fails.append(f"{app}: exported {c['kind']} {c['name']} has permission {c['permission']!r}, "
                         f"allowlist says {allowlist[key]!r}")
    for key in sorted(set(allowlist) - seen):
        fails.append(f"{app}: allowlisted {key[0]} {key[1]} is not exported in the APK (stale allowlist)")
    return fails


def check_not_debuggable(app, facts):
    return [f"{app}: release APK is debuggable"] if facts["debuggable"] else []


# ---------------------------------------------------------------------------------------------
# APK contents: dex string tables, resource strings, zip entries
# ---------------------------------------------------------------------------------------------

def _uleb128(buf, off):
    result = shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, off
        shift += 7


def dex_strings(data):
    """All strings in a dex file's string_ids table (MUTF-8 decoded leniently)."""
    if data[:4] != b"dex\n":
        raise ValueError("not a dex file")
    size, ids_off = struct.unpack_from("<II", data, 0x38)
    out = []
    for i in range(size):
        (str_off,) = struct.unpack_from("<I", data, ids_off + 4 * i)
        _, start = _uleb128(data, str_off)
        end = data.index(b"\x00", start)
        out.append(data[start:end].decode("utf-8", errors="replace"))
    return out


def apk_dex_strings(apk_path):
    out = []
    with zipfile.ZipFile(apk_path) as z:
        for name in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                out += dex_strings(z.read(name))
    return out


def apk_entries(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        return z.namelist()


def _prefix_forms(prefix):
    dotted = prefix.rstrip(".")
    slashed = dotted.replace(".", "/")
    return dotted, slashed, "L" + slashed + "/"


def check_forbidden_in_apk(app, dex, res_strings, entries, facts, class_prefixes, dex_exceptions=frozenset()):
    """Fail if any forbidden package shows up in dex types/strings, resources, zip entries or manifest.

    dex_exceptions: exact dex strings (not descriptors or prefixes) documented in forbidden-exceptions.txt.
    """
    fails = []
    manifest_names = facts["uses_permissions"] + facts["app_meta"] + [c["name"] for c in facts["components"]]
    for prefix in class_prefixes:
        dotted, slashed, descriptor = _prefix_forms(prefix)
        hits = [s for s in dex if (s.startswith(descriptor) or s.startswith(dotted + ".")) and s not in dex_exceptions]
        if hits:
            fails.append(f"{app}: dex references {prefix} ({len(hits)} strings, e.g. {hits[0]})")
        rhits = [s for s in res_strings if dotted in s]
        if rhits:
            fails.append(f"{app}: resources reference {prefix} (e.g. {rhits[0]!r})")
        ehits = [e for e in entries if slashed in e or dotted in e]
        if ehits:
            fails.append(f"{app}: APK entries reference {prefix} (e.g. {ehits[0]})")
        mhits = [n for n in manifest_names if n.startswith(dotted)]
        if mhits:
            fails.append(f"{app}: manifest references {prefix} (e.g. {mhits[0]})")
    # Play services / Firebase also announce themselves through version files and resources.
    vhits = [e for e in entries if re.search(r"META-INF/(play-services|firebase|com\.google\.android\.gms)", e)]
    if vhits:
        fails.append(f"{app}: APK contains Play/Firebase version files (e.g. {vhits[0]})")
    for marker in ("google_app_id", "gcm_defaultSenderId", "com.google.android.gms.version"):
        if marker in res_strings or marker in facts["app_meta"]:
            fails.append(f"{app}: APK carries Play/Firebase marker {marker}")
    return fails


def check_deps(module, coords, group_prefixes):
    fails = []
    for coord in coords:
        group = coord.split(":", 1)[0]
        for prefix in group_prefixes:
            if group == prefix or group.startswith(prefix + "."):
                fails.append(f"{module}: dependency {coord} is forbidden ({prefix})")
    return fails


# ---------------------------------------------------------------------------------------------
# Retired names
# ---------------------------------------------------------------------------------------------

def retired_patterns(names):
    pats = []
    for n in names:
        tail = r"(?![A-Za-z0-9])" if n[-1].isalnum() else ""
        pats.append((n, re.compile(r"(?<![A-Za-z0-9])" + re.escape(n) + tail, re.IGNORECASE)))
    return pats


def check_retired(texts, names):
    """texts: {label: str}. Fails once per (label, name)."""
    fails = []
    pats = retired_patterns(names)
    for label, text in texts.items():
        for name, pat in pats:
            m = pat.search(text)
            if m:
                line = text.count("\n", 0, m.start()) + 1
                fails.append(f"retired name {name!r} in {label}:{line}")
    return fails


def repo_files(root):
    """Tracked plus untracked-but-not-ignored files, relative to root."""
    try:
        out = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             check=True, capture_output=True).stdout
        return sorted({p for p in out.decode().split("\0") if p and (Path(root) / p).is_file()})
    except (OSError, subprocess.CalledProcessError):
        files = []
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in (".git", ".gradle", "build", ".tools")]
            files += [os.path.relpath(os.path.join(dirpath, f), root) for f in filenames]
        return sorted(files)


def retired_scan_texts(root, files):
    texts = {}
    for rel in files:
        top = rel.split("/", 1)[0]
        if rel == RETIRED_NAMES_FILE:
            continue
        if not (rel in RETIRED_SCAN_ROOT_FILES or ("/" in rel and top in RETIRED_SCAN_DIRS)):
            continue
        data = (Path(root) / rel).read_bytes()
        if b"\x00" in data[:8192]:
            continue  # binary (gradle-wrapper.jar); its contents are Gradle's, not ours
        texts[rel] = data.decode("utf-8", errors="replace")
    return texts


# ---------------------------------------------------------------------------------------------
# Signing
# ---------------------------------------------------------------------------------------------

# apksigner labels vary by build-tools version ("Signer #1 certificate", build-tools 37: "V2 Signer: certificate"),
# so match the first line that names a signer.
_DIGEST_RE = re.compile(r"^[^\n]*Signer[^\n]*?certificate SHA-256 digest: ([0-9a-f]+)", re.M)


def signer_digest(apksigner, apk):
    res = subprocess.run([apksigner, "verify", "--print-certs", str(apk)], capture_output=True, text=True)
    if res.returncode != 0:
        # Surface why apksigner rejected the APK (CI diagnosis); unsigned APKs also land here.
        err = (res.stderr or res.stdout).strip().splitlines()
        if err:
            print(f"NOTE apksigner ({apksigner}) on {apk}: {err[-1]}", file=sys.stderr)
        return None
    m = _DIGEST_RE.search(res.stdout)
    if not m:
        print(f"NOTE apksigner ({apksigner}) printed no signer digest for {apk}: {res.stdout.strip()[:300]!r}", file=sys.stderr)
    return m.group(1) if m else None


def check_signers(digests, require_signed_release):
    """digests: {(app, 'debug'|'release'): sha256 hex or None (unsigned)}."""
    fails, notes = [], []
    for app in APPS:
        if digests.get((app, "debug")) is None:
            fails.append(f"{app}: debug APK is not signed")
    rel = {app: digests.get((app, "release")) for app in APPS}
    if all(v is None for v in rel.values()):
        if require_signed_release:
            fails.append("release APKs are unsigned but --require-signed-release was given")
        else:
            notes.append("release APKs are unsigned (no release key configured); cert checks skipped")
        return fails, notes
    for app in APPS:
        if rel[app] is None:
            fails.append(f"{app}: release APK is unsigned while the other app's release is signed")
        elif rel[app] == digests.get((app, "debug")):
            fails.append(f"{app}: release and debug APKs share signing cert {rel[app][:16]}…")
    if rel["actuator"] and rel["session"] and rel["actuator"] != rel["session"]:
        fails.append("actuator and session release certs differ; signature permissions need one signer")
    return fails, notes


# ---------------------------------------------------------------------------------------------
# gitleaks
# ---------------------------------------------------------------------------------------------

def find_gitleaks(root):
    local = Path(root) / ".tools" / "gitleaks"
    if local.is_file() and os.access(local, os.X_OK):
        return str(local)
    return shutil.which("gitleaks")


def run_gitleaks_dir(gitleaks, directory):
    """Returns (clean, output)."""
    res = subprocess.run([gitleaks, "dir", "--no-banner", "--redact", "--exit-code", "1", str(directory)],
                         capture_output=True, text=True)
    return res.returncode == 0, (res.stdout + res.stderr).strip()


def copy_repo_files(root, files, dest):
    for rel in files:
        src = Path(root) / rel
        dst = Path(dest) / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)


# ---------------------------------------------------------------------------------------------
# SDK tools
# ---------------------------------------------------------------------------------------------

def find_sdk(root):
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env) and Path(os.environ[env]).is_dir():
            return Path(os.environ[env])
    lp = Path(root) / "local.properties"
    if lp.is_file():
        for line in lp.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip())
    raise SystemExit("policy_check: Android SDK not found (ANDROID_HOME or local.properties sdk.dir)")


def build_tool(sdk, name):
    versions = sorted((p for p in (sdk / "build-tools").iterdir() if (p / name).exists()),
                      key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    if not versions:
        raise SystemExit(f"policy_check: {name} not found under {sdk}/build-tools")
    return str(versions[-1] / name)


def find_apk(root, app, build_type):
    d = Path(root) / app / "build" / "outputs" / "apk" / build_type
    for name in (f"{app}-{build_type}.apk", f"{app}-{build_type}-unsigned.apk"):
        if (d / name).is_file():
            return d / name
    raise SystemExit(f"policy_check: no {build_type} APK for {app} in {d}; run ./gradlew assemble first")


def aapt2_out(aapt2, *args):
    return subprocess.run([aapt2, *args], check=True, capture_output=True, text=True).stdout


def platform_permission_levels(aapt2, sdk):
    """(jar, {permission: protectionLevel}) from the framework manifest inside the newest SDK android.jar."""
    jars = sorted((p for p in (sdk / "platforms").glob("android-*/android.jar")),
                  key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[-.]", p.parent.name)])
    if not jars:
        raise SystemExit(f"policy_check: no platforms/android-*/android.jar under {sdk}")
    xml = aapt2_out(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(jars[-1]))
    levels = {k: v for k, v in declared_permissions(parse_xmltree(xml)).items() if v is not None}
    if not levels:
        raise SystemExit(f"policy_check: no permission protection levels in {jars[-1]}")
    return jars[-1], levels


def resource_strings(aapt2, apk):
    out = aapt2_out(aapt2, "dump", "strings", str(apk))
    return [m.group(1) for m in re.finditer(r"^String #\d+ : (.*)$", out, re.MULTILINE)]


# ---------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--gitleaks", choices=("auto", "required", "off"), default="auto")
    ap.add_argument("--require-signed-release", action="store_true",
                    default=os.environ.get("VOS_REQUIRE_SIGNED_RELEASE") == "1")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    ci = root / "ci"

    sdk = find_sdk(root)
    aapt2 = build_tool(sdk, "aapt2")
    apksigner = build_tool(sdk, "apksigner")
    groups, class_prefixes, actuator_classes = read_forbidden(ci / "forbidden-artifacts.txt")
    exceptions = read_exceptions(ci / "forbidden-exceptions.txt")
    retired = read_list(ci / "retired-names.txt")
    platform_jar, platform_levels = platform_permission_levels(aapt2, sdk)
    print(f"session permissions are checked against {len(platform_levels)} platform protection levels "
          f"({platform_jar.relative_to(sdk)})")
    denied = {
        "actuator": read_list(ci / "actuator-denied-permissions.txt"),
        "session": read_list(ci / "session-denied-permissions.txt") + read_list(ci / "privileged-permissions.txt"),
    }

    fails, notes = [], []
    apk_text = {}
    digests = {}
    for app in APPS:
        for build_type in ("release", "debug"):
            apk = find_apk(root, app, build_type)
            digests[(app, build_type)] = signer_digest(apksigner, apk)
            xml = aapt2_out(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk))
            facts = manifest_facts(parse_xmltree(xml))
            dex = apk_dex_strings(apk)
            res = resource_strings(aapt2, apk)
            entries = apk_entries(apk)
            fails += check_package(app, facts)
            fails += check_denied_permissions(app, facts, denied[app])
            fails += check_own_permission(app, facts)
            if app == "session":
                fails += check_no_privileged_permissions(app, facts, platform_levels)
                fails += check_no_sensitive_components(app, facts)
            fails += check_exported(app, facts, exported_allowlist(ci, app, build_type))
            prefixes = class_prefixes + (actuator_classes if app == "actuator" else [])
            fails += check_forbidden_in_apk(app, dex, res, entries, facts, prefixes, exceptions.get(app, frozenset()))
            if build_type == "release":
                fails += check_not_debuggable(app, facts)
                apk_text[f"{app}-release/manifest.txt"] = xml
                apk_text[f"{app}-release/dex-strings.txt"] = "\n".join(dex)
                apk_text[f"{app}-release/resource-strings.txt"] = "\n".join(res)
                apk_text[f"{app}-release/entries.txt"] = "\n".join(entries)
            print(f"checked {apk.relative_to(root)}: {len(facts['uses_permissions'])} permissions, "
                  f"{sum(c['exported'] for c in facts['components'])} exported components, {len(dex)} dex strings")

    for module in ("actuator", "session", "vtb-sdk", "ipc"):
        deps_file = root / module / "build" / "policy" / "deps.txt"
        if not deps_file.is_file():
            fails.append(f"{module}: {deps_file.relative_to(root)} missing; run ./gradlew writePolicyDeps")
            continue
        coords = [c for c in deps_file.read_text().splitlines() if c.strip() and not c.startswith("#")]
        fails += check_deps(module, coords, groups)
        print(f"checked {module} dependency graph: {len(coords)} external modules")

    files = repo_files(root)
    texts = retired_scan_texts(root, files)
    fails += check_retired(texts, retired)
    fails += check_retired({f"APK:{k}": v for k, v in apk_text.items()}, retired)
    print(f"checked retired names over {len(texts)} repo files and {len(apk_text)} APK dumps")

    sfails, snotes = check_signers(digests, args.require_signed_release)
    fails += sfails
    notes += snotes

    if args.gitleaks == "off":
        notes.append("gitleaks: off")
    else:
        gl = find_gitleaks(root)
        if gl is None:
            msg = "gitleaks not found (run ci/fetch-gitleaks.sh)"
            if args.gitleaks == "required":
                fails.append(msg)
            else:
                notes.append(f"SKIPPED gitleaks: {msg}")
        else:
            with tempfile.TemporaryDirectory(prefix="vos-policy-") as tmp:
                repo_copy = Path(tmp) / "repo"
                copy_repo_files(root, files, repo_copy)
                clean, out = run_gitleaks_dir(gl, repo_copy)
                if not clean:
                    fails.append("gitleaks found secrets in the repo:\n" + out)
                strings_dir = Path(tmp) / "apk-strings"
                for rel, text in apk_text.items():
                    p = strings_dir / rel
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_text(text, encoding="utf-8")
                clean2, out2 = run_gitleaks_dir(gl, strings_dir)
                if not clean2:
                    fails.append("gitleaks found secrets in the APK strings:\n" + out2)
            print(f"gitleaks ({gl}) over {len(files)} repo files and the release APK strings")

    for n in notes:
        print(f"NOTE {n}")
    for f in fails:
        print(f"FAIL {f}")
    print("policy check: " + ("FAILED" if fails else "PASSED"))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
