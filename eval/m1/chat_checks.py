"""The M1-CHAT-01 checks of eval/m1/chat.sh (#69), read from a finished run's files only (#299).

The checks need no phone, so each one can be shown to fail: --self-test builds a passing run in a
temp dir, then breaks it once for each way a check can be broken, and that check must FAIL. Every
check must have at least one such break, so a check that cannot fail fails the self-test.

Usage: python3 -I eval/m1/chat_checks.py RUN_DIR    the summary and one PASS or FAIL line per check;
                                                    exits 1 if any check fails, 2 if there is no
                                                    such run directory (and no check is run)
       python3 -I eval/m1/chat_checks.py --self-test

RUN_DIR holds what chat.sh pulled. A missing file reads as empty, so its checks fail; the summary's
first line names each of these files that is missing or empty (#305), so a failed pull or an aborted
run shows as that and not only as unrelated FAIL lines:
  facts.txt            key=value: typed_from and summon_from (device time, MM-DD HH:MM:SS), in_session
                       (1 if the held typed turn was open in the session before the summon),
                       stop_in_session (the same, before the stop leg's tile press), stop_pressed (1 if
                       the shell pressed the tile: it does not when the turn had already ended),
                       actuator_uid, forge_audit_from and forge_audit_to (the forged-reply leg's lines in
                       actuator-audit.jsonl, 0-based, end exclusive)
  voice.txt            logcat of the voice leg (VOS_VOICE, VOS_TURN, VOS_CONTEXT)
  transcripts.jsonl    the session's transcript lines from this run
  actuator-audit.jsonl the actuator's audit lines from this run
  summon.txt           VOS_TURN logcat of the summon leg; summon-typed.txt: its VOS_TYPED logcat
  stop.txt             logcat of the stop leg (VOS_TYPED, VOS_AUDIT, VOS_TURN), from its typed send to past
                       the session's hold
  recording.txt        the whole of `dumpsys audio`; the checks read its recording log (#333): the
                       EventLogger that RecordingActivityMonitor.dump prints after its
                       "RecordActivityMonitor dump time:" header, titled "Events log: recording activity
                       received by AudioService" ("Audio event log: ..." before Android 14), one event per
                       line up to a blank line. No such log, more than one, a line in it that is not an
                       event, or a full log that may have dropped events from a leg: that leg's AudioRecord
                       check fails, never reads as zero recordings.
"""
import contextlib
import io
import json
import re
import shutil
import sys
import tempfile
from pathlib import Path

TYPED_TURNS = 8
# VoiceTurn's VOS_TURN lines when a voice turn starts (the session opened it; the live mic started).
VOICE_START = re.compile(r"capture turnId=|turn opened in session turnId=")
STAMP = re.compile(r"\d\d-\d\d \d\d:\d\d:\d\d")
# The audio service's recording log in `dumpsys audio` (#333): RecordingActivityMonitor's EventLogger,
# its title (the tag has varied by release, hence "recording activity" and anything after it), then
# its events, oldest first, each "MM-dd HH:mm:ss:SSS " and the event, e.g. a RecordingEvent's
# "rec start riid:N uid:N session:N src:NAME not silenced pack:NAME".
REC_LOG_TITLE = re.compile(r"(?:Events log|Audio event log): recording activity\b.*")
REC_EVENT = re.compile(r"(\d\d-\d\d \d\d:\d\d:\d\d)[:.]\d{3} (.*)")
REC_START = re.compile(r"rec (?:start|update) .*")
REC_LOG_CAPACITY = 50  # the EventLogger keeps the last 50 events; a full one may have dropped older ones
TIME = re.compile(r"\b\d{1,2}(:\d{2})?\s*(am|pm|a\.m\.|p\.m\.)|\b\d{1,2}:\d{2}\b")
ACTUATOR = "com.vos.actuator"
# The stop leg's press: DebugSummonActivity op tile, a "Stop VOS" press from source debug_tile.
STOP_REASON = "owner stop: debug_tile"
# The end the stop must have: the session's endTurn for the actuator's close, not the stop backstop.
STOP_ENDED_BY = "session: closed by the actuator"
STOP_MAX_MS = 500
# A logcat line (threadtime): its tag and message.
LOGCAT = re.compile(r"\s[VDIWEF] ([^\s:]+)\s*: (.*)")
# The files chat.sh writes for the checks, in the order the summary names them.
RUN_FILES = ("facts.txt", "voice.txt", "transcripts.jsonl", "actuator-audit.jsonl", "summon.txt",
             "summon-typed.txt", "stop.txt", "recording.txt")


def read(d, name):
    p = Path(d) / name
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def unread(d):
    """Each of RUN_FILES in d that is missing or empty (blank counts as empty), as "name (missing)"."""
    return [f"{name} ({'empty' if (Path(d) / name).is_file() else 'missing'})"
            for name in RUN_FILES if not read(d, name).strip()]


def parse(lines):
    """JSON objects from JSONL lines, and how many non-blank lines did not parse as one."""
    out, bad = [], 0
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            o = json.loads(line)
        except json.JSONDecodeError:
            o = None
        if isinstance(o, dict):
            out.append(o)
        else:
            bad += 1
    return out, bad


def recording_log(dump):
    """The recording log in a `dumpsys audio` dump: ([(MM-DD HH:MM:SS, event)], None), oldest first, or
    (None, why it cannot be read). Its events run from the title to the next blank line or the end."""
    lines = [line.strip() for line in dump.splitlines()]
    titles = [i for i, line in enumerate(lines) if REC_LOG_TITLE.fullmatch(line)]
    if len(titles) != 1:
        return None, "missing" if not titles else f"unreadable: {len(titles)} recording logs"
    events = []
    for line in lines[titles[0] + 1:]:
        if not line:
            break
        m = REC_EVENT.fullmatch(line)
        if not m:  # e.g. a dumpsys timeout cut it short
            return None, f"unreadable: not an event: {line[:60]!r}"
        events.append((m.group(1), m.group(2)))
    return events, None


def facts_of(text):
    facts = {}
    for line in text.splitlines():
        k, sep, v = line.partition("=")
        if sep:
            facts[k.strip()] = v.strip()
    return facts


def as_int(s):
    try:
        return int(s)
    except (TypeError, ValueError):
        return None


def evaluate(d):
    """Returns (summary lines, [(check id, passed, text)])."""
    facts = facts_of(read(d, "facts.txt"))
    voice = read(d, "voice.txt")
    tr, _ = parse(read(d, "transcripts.jsonl").splitlines())
    audit_lines = read(d, "actuator-audit.jsonl").splitlines()
    audit, _ = parse(audit_lines)
    summon = read(d, "summon.txt")
    summon_typed = read(d, "summon-typed.txt")
    stop_log = read(d, "stop.txt")
    rec_log = read(d, "recording.txt")

    def events(lines, name):
        return [a for a in lines if a.get("event") == name]

    def owner_turn(words):
        """The first owner line with all of words: (its turn id, VOS's reply in that turn or "")."""
        for i, t in enumerate(tr):
            if t.get("role") == "owner" and all(w in str(t.get("text", "")).lower() for w in words):
                for u in tr[i + 1:]:
                    if u.get("turn_id") == t.get("turn_id") and u.get("role") == "vos":
                        return t.get("turn_id"), str(u.get("text", ""))
                return t.get("turn_id"), ""
        return None, ""

    opened = events(audit, "typed.turn_opened")
    opened_ids = {o.get("turnId") for o in opened}
    ended = events(audit, "typed.turn_ended")
    answered_ids = {a.get("turnId") for a in ended if a.get("reason") == "session: typed reply"}
    tools = [a for a in events(audit, "tool.call") if a.get("typed")]
    sources = {o.get("turnId"): o.get("source") for o in opened}
    _, time_reply = owner_turn(["what time is it?"])
    pause_id, _ = owner_turn(["pause the music"])
    _, dana_reply = owner_turn(["sister", "called"])
    now_id, now_reply = owner_turn(["what time is it now"])

    # The forged-reply leg: the actuator's audit lines from the shell's typed send to that turn's end.
    f_from, f_to = as_int(facts.get("forge_audit_from")), as_int(facts.get("forge_audit_to"))
    leg_ok = f_from is not None and f_to is not None and 0 <= f_from < f_to <= len(audit_lines)
    leg, leg_bad = parse(audit_lines[f_from:f_to] if leg_ok else [])
    forged = [a for a in events(leg, "typed.reply_refused") if str(a.get("turnId", "")).startswith("forged-")]
    forged_ids = {a.get("turnId") for a in forged}
    leg_opened = events(leg, "typed.turn_opened")
    own = {o.get("turnId") for o in leg_opened}
    leg_voice = events(leg, "audio.transition")
    # Any other turn touched in the leg, or a forged id among the turns the run opened.
    foreign = [a for a in leg if a.get("turnId") and a.get("event") != "typed.reply_refused"
               and a.get("turnId") not in own]
    foreign += [o for o in opened if o.get("turnId") in forged_ids]

    rec_events, rec_problem = recording_log(rec_log)
    uid = facts.get("actuator_uid", "")
    actuator_rec = re.compile(r"\bpack:" + re.escape(ACTUATOR) + r"(\s|$)"
                              + (r"|\buid:" + re.escape(uid) + r"\b" if uid.isdigit() else ""))

    def rec_since(stamp):
        """(the actuator's AudioRecord starts in the recording log at or after stamp, a note on the log).
        The count is None when there is no stamp, no readable log, or the log is full and its oldest
        event is not before stamp: it may have dropped some from then."""
        if rec_events is None:
            return None, f"recording log {rec_problem}"
        note = f"recording log: {len(rec_events)} events"
        if not STAMP.fullmatch(stamp or ""):
            return None, note
        if len(rec_events) >= REC_LOG_CAPACITY and rec_events[0][0] >= stamp:
            return None, f"{note}, full since {rec_events[0][0]}: it may have dropped some from {stamp}"
        return sum(1 for t, e in rec_events
                   if t >= stamp and REC_START.fullmatch(e) and actuator_rec.search(e)), note

    # The stop leg: the turn the tile press stopped, and everything of it after the stop.
    stops = events(audit, "typed.turn_stop")
    stopped = [a for a in ended if a.get("reason") == STOP_REASON]
    sid = stopped[0].get("turnId") if stopped else None
    stop_line = next((a for a in stops if a.get("turnId") == sid), None) if sid else None
    late = [a for a in audit if sid and a.get("turnId") == sid
            and a.get("event") in ("typed.reply_refused", "tool.call", "typed.model_call_refused")]
    stop_roles = [t.get("role") for t in tr if sid and t.get("turn_id") == sid]
    _, after_reply = owner_turn(["thanks, that is all"])

    def t_up(pattern):
        m = re.search(pattern, stop_log) if sid else None
        return int(m.group(1)) if m else None

    t_stop = t_up(r"typed turn stop turnId=" + re.escape(str(sid)) + r" .*?t_up=(\d+)")
    t_end = t_up(r"typed turn ended turnId=" + re.escape(str(sid)) + r" t_up=(\d+) reason=")
    stop_ms = t_end - t_stop if t_stop is not None and t_end is not None else None
    stop_turn = "\n".join(m.group(2) for m in map(LOGCAT.search, stop_log.splitlines())
                          if m and m.group(1) == "VOS_TURN")
    stop_logged = sid is not None and t_stop is not None  # the press reached TypedChat.stop: in the same log

    rec_typed, rec_typed_note = rec_since(facts.get("typed_from"))
    rec_summon, rec_summon_note = rec_since(facts.get("summon_from"))
    voice_control = VOICE_START.search(voice) is not None

    gaps = unread(d)
    summary = [
        (f"run files missing or empty ({len(gaps)} of {len(RUN_FILES)}): " + ", ".join(gaps)) if gaps
        else f"run files: all {len(RUN_FILES)} present",
        f"opened={len(opened)} answered={len(answered_ids & opened_ids)} forged_refused={len(forged)} "
        f"sources={','.join(sorted(map(str, set(sources.values()))))}",
        f"time_reply={time_reply!r}",
        f"followup_reply={dana_reply!r}",
        f"now_reply={now_reply!r}",
        f"forge_leg=lines {f_from}..{f_to} opened={len(leg_opened)} voice_transitions={len(leg_voice)} "
        f"foreign={len(foreign)} unreadable={leg_bad}",
        f"stop: pressed={facts.get('stop_pressed')} turn={sid} stops={len(stops)} stopped={len(stopped)} "
        f"ms={stop_ms} ended_by={stopped[0].get('ended_by') if stopped else None!r} late={len(late)} "
        f"roles={','.join(map(str, stop_roles)) or 'none'}",
        f"after_stop_reply={after_reply!r}",
    ]
    results = []

    def check(cid, ok, text):
        results.append((cid, bool(ok), text))

    check("voice_ended", " over after_ms=" in voice, "the voice turn ended")
    check("typed_answered", len(opened) == TYPED_TURNS and answered_ids >= opened_ids - {sid},
          f"every typed turn but the stopped one was answered ({len(answered_ids & opened_ids)} of {len(opened)})")
    check("time_reply", TIME.search(time_reply.lower()), "the time reply gives a time")
    check("media_control", pause_id and any(a.get("tool") == "media_control" and a.get("turnId") == pause_id
                                            for a in tools),
          "\"pause the music\" ran media_control for the typed turn")
    check("followup_dana", "dana" in dana_reply.lower(),
          "the typed follow-up names Dana from the voice turn (shared conversation)")
    check("direct_reply", now_id and sources.get(now_id) == "debug_notification" and now_reply.strip(),
          f"the direct-reply turn was answered (its source: {sources.get(now_id)})")
    check("rec_typed", rec_typed == 0,
          f"no AudioRecord started for the actuator during the typed turns ({rec_typed}; {rec_typed_note})")
    check("forged_refused", leg_ok and forged,
          f"the session's forged reply was refused and audited ({len(forged)} in its leg)")
    check("forged_no_turn", leg_ok and forged and len(leg_opened) == 1 and not leg_voice and not foreign
          and not leg_bad,
          "the forged reply opened no turn (" + (
              f"in its leg: typed turns opened {len(leg_opened)}, the shell's 1; voice transitions "
              f"{len(leg_voice)}; lines for another turn {len(foreign)}; unreadable {leg_bad})" if forged
              else "no forged reply in its leg)"))
    check("summon_during", facts.get("in_session") == "1" and "typed turn ended" not in summon_typed,
          "the summon came while the typed turn was open in the session")
    check("summon_refused", "summon refused: a typed turn is open" in summon,
          "the summon during the typed turn was refused")
    check("summon_no_turn", voice_control and not VOICE_START.search(summon),
          "the refused summon started no voice turn (the same lines show the voice leg's turn: "
          f"{'yes' if voice_control else 'no'})")
    check("rec_summon", rec_summon == 0,
          f"no AudioRecord started for the refused summon ({rec_summon}; {rec_summon_note})")
    check("stop_during", facts.get("stop_pressed") == "1" and facts.get("stop_in_session") == "1"
          and stop_line is not None and stop_line.get("in_session") is True,
          "the stop came while the typed turn was open in the session (pressed: "
          f"{'yes' if facts.get('stop_pressed') == '1' else 'no'})")
    check("stop_no_voice", facts.get("stop_pressed") == "1" and stop_logged and voice_control
          and not VOICE_START.search(stop_turn),
          "the tile press started no voice turn (its typed stop is in the same log: "
          f"{'yes' if stop_logged else 'no'}; the same lines show the voice leg's turn: "
          f"{'yes' if voice_control else 'no'})")
    check("stop_owner", len(stops) == 1 and len(stopped) == 1 and stop_line is not None,
          f"\"Stop VOS\" ended the typed turn as the owner's stop ({len(stopped)} of {len(stops)})")
    check("stop_fast", stop_ms is not None and 0 <= stop_ms <= STOP_MAX_MS,
          f"the stopped turn ended within {STOP_MAX_MS} ms ({stop_ms} ms)")
    check("stop_session_end", stopped and stopped[0].get("ended_by") == STOP_ENDED_BY,
          "the session closed its side first: the end was its endTurn, not the stop backstop "
          f"({stopped[0].get('ended_by') if stopped else 'no stopped turn'})")
    check("stop_no_late", sid is not None and not late,
          "nothing of the stopped turn reached the actuator after the stop ("
          + (f"{len(late)} lines" if sid else "no stopped turn") + ")")
    check("stop_no_vos", sid is not None and set(stop_roles) <= {"owner"},
          "the stopped turn wrote no VOS line ("
          + ((",".join(map(str, stop_roles)) or "none") if sid else "no stopped turn") + ")")
    check("after_stop", after_reply.strip(), "the typed turn after the stop was answered")
    return summary, results


def main(d):
    if not Path(d).is_dir():
        print(f"chat_checks.py: no such run directory: {d}", file=sys.stderr)
        return 2
    summary, results = evaluate(d)
    for line in summary:
        print(line)
    for _, ok, text in results:
        print(("PASS " if ok else "FAIL ") + text)
    return 0 if all(ok for _, ok, _ in results) else 1


# --- self-test: a passing run, and a break for each way a check can fail ---

VOICE_ID = "11111111-1111-4111-8111-111111111111"
IDS = [f"2222222{i}-2222-4222-8222-222222222222" for i in range(1, TYPED_TURNS + 1)]
FORGED = "forged-33333333-3333-4333-8333-333333333333"
TURNS = [  # (source, owner text, VOS reply, tool)
    ("debug_window", "What time is it?", "It's 9:05 AM.", "time_get"),
    ("debug_window", "Pause the music.", "Paused.", "media_control"),
    ("debug_window", "What did I tell you my sister is called?", "Dana.", None),
    ("debug_notification", "What time is it now?", "It's 9:06 AM.", "time_get"),
    ("debug_window", "Thanks.", "You're welcome.", None),
    ("debug_window", "What day is it?", "Saturday.", "time_get"),
    ("debug_window", "Count slowly from one to ten.", None, None),
    ("debug_window", "Thanks, that is all.", "Glad to help.", None),
]
FORGE_TURN = 4  # "Thanks.": the session forges a reply during it
STOP_TURN = 6  # "Count slowly ...": the shell stops it with a tile press during the session's hold
UID = "10234"


def write_passing(d):
    d = Path(d)
    tr = [{"turn_id": VOICE_ID, "role": "owner", "text": "My sister's name is Dana."},
          {"turn_id": VOICE_ID, "role": "vos", "text": "Got it."}]
    audit = [{"event": "audio.transition", "state": s, "turnId": VOICE_ID}
             for s in ("CAPTURE", "CONVERSE", "RESTORE", "IDLE")]
    forge_from = forge_to = None
    for i, (source, text, reply, tool) in enumerate(TURNS):
        tid = IDS[i]
        if i == FORGE_TURN:
            forge_from = len(audit)
        audit.append({"event": "typed.turn_opened", "turnId": tid, "source": source, "chars": len(text)})
        if i == FORGE_TURN:
            audit.append({"event": "typed.reply_refused", "turnId": FORGED, "chars": 26})
        if tool:
            audit.append({"event": "tool.call", "turnId": tid, "typed": True, "tool": tool, "status": "VERIFIED"})
        if i == STOP_TURN:
            audit.append({"event": "typed.turn_stop", "turnId": tid, "source": "debug_tile", "in_session": True})
            audit.append({"event": "typed.turn_ended", "turnId": tid, "reason": STOP_REASON, "ended_by": STOP_ENDED_BY})
        else:
            audit.append({"event": "typed.turn_ended", "turnId": tid, "reason": "session: typed reply"})
        if i == FORGE_TURN:
            forge_to = len(audit)
        tr.append({"turn_id": tid, "role": "owner", "text": text})
        if reply is not None:
            tr.append({"turn_id": tid, "role": "vos", "text": reply})
    (d / "transcripts.jsonl").write_text("".join(json.dumps(t) + "\n" for t in tr))
    (d / "actuator-audit.jsonl").write_text("".join(json.dumps(a) + "\n" for a in audit))
    (d / "facts.txt").write_text(
        f"typed_from=10-31 12:00:10\nsummon_from=10-31 12:01:00\nin_session=1\nstop_in_session=1\nstop_pressed=1\n"
        f"actuator_uid={UID}\n"
        f"forge_audit_from={forge_from}\nforge_audit_to={forge_to}\n")
    (d / "voice.txt").write_text(
        f"10-31 12:00:01.000 100 101 I VOS_TURN: turn opened in session turnId={VOICE_ID} after_ms=120\n"
        f"10-31 12:00:08.000 200 201 I VOS_VOICE: turn {VOICE_ID} over after_ms=7000 reason=model end\n")
    (d / "summon.txt").write_text("10-31 12:01:00.500 100 101 I VOS_TURN: summon refused: a typed turn is open\n")
    (d / "summon-typed.txt").write_text(f"10-31 12:00:59.000 100 102 I VOS_TYPED: typed turn opened in session "
                                        f"turnId={IDS[5]}\n")
    sid = IDS[STOP_TURN]
    (d / "stop.txt").write_text(
        f"10-31 12:01:20.000 100 102 I VOS_TYPED: typed turn opened in session turnId={sid}\n"
        f"10-31 12:01:21.000 100 100 I VOS_TYPED: typed turn stop turnId={sid} source=debug_tile t_up=500000\n"
        f"10-31 12:01:21.010 300 301 I VOS_TYPED: typed turn {sid} closed by the actuator ms=4\n"
        f"10-31 12:01:21.040 100 100 I VOS_TYPED: typed turn ended turnId={sid} t_up=500040 reason={STOP_REASON}\n")
    (d / "recording.txt").write_text(DUMPSYS_AUDIO)


# `dumpsys audio` as AOSP's AudioService.dump prints it (#333), cut down: the sections around the
# recording log, RecordingActivityMonitor.dump's header, its recorders and two blank lines, then its
# EventLogger. Controls in the log that must not count: the actuator recording before the typed legs,
# another app during them, and stops and releases.
DUMPSYS_AUDIO = f"""\
Events log: focus commands as seen by MediaFocusControl
10-31 11:00:00:010 requestAudioFocus() from uid/pid {UID}/4321 AA=USAGE_ASSISTANT/CONTENT_TYPE_SPEECH \
clientId=android.media.AudioManager@1a2b3c focusReq=AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE flags=0x0 sdk:35

PlaybackActivityMonitor dump time: 12:02:30 PM

  players:
  ducked players piids:

RecordActivityMonitor dump time: 12:02:30 PM
riid 1; active false


Events log: recording activity received by AudioService
10-31 11:00:00:000 rec start riid:1 uid:{UID} session:9 src:VOICE_COMMUNICATION not silenced pack:{ACTUATOR}
10-31 11:00:07:500 rec stop riid:1 uid:{UID} session:9 src:VOICE_COMMUNICATION pack:{ACTUATOR}
10-31 11:00:07:520 rec release riid:1 uid:{UID} session:9 src:VOICE_COMMUNICATION pack:{ACTUATOR}
10-31 12:00:30:000 rec start riid:2 uid:10111 session:10 src:MIC not silenced pack:com.example.recorder
10-31 12:00:40:000 rec stop riid:2 uid:10111 session:10 src:MIC pack:com.example.recorder

AudioDeviceBroker:
  Message handler (watch for unhandled messages):
"""


def edit(name, fn):
    def apply(d):
        p = Path(d) / name
        p.write_text(fn(p.read_text() if p.exists() else ""))
    return apply


def remove(name):
    return lambda d: (Path(d) / name).unlink()


def fact(key, value):
    return edit("facts.txt", lambda t: re.sub(rf"(?m)^{key}=.*$", f"{key}={value}", t))


def audit_edit(fn):
    """fn(entries) edits the run's audit: a list of [line, in the forged leg] pairs, where a line is an
    object or a raw string written as it is. The leg's bounds in facts.txt follow its lines."""
    def apply(d):
        p = Path(d) / "actuator-audit.jsonl"
        facts = facts_of(read(d, "facts.txt"))
        start, end = int(facts["forge_audit_from"]), int(facts["forge_audit_to"])
        entries = [[json.loads(line), start <= i < end] for i, line in enumerate(p.read_text().splitlines())]
        fn(entries)
        p.write_text("".join((e[0] if isinstance(e[0], str) else json.dumps(e[0])) + "\n" for e in entries))
        leg = [i for i, e in enumerate(entries) if e[1]]
        facts["forge_audit_from"], facts["forge_audit_to"] = (leg[0], leg[-1] + 1) if leg else (start, start)
        (Path(d) / "facts.txt").write_text("".join(f"{k}={v}\n" for k, v in facts.items()))
    return apply


def matches(entry, event, turn_id):
    a = entry[0]
    return isinstance(a, dict) and a.get("event") == event and (turn_id is None or a.get("turnId") == turn_id)


def in_forge_leg(line):
    """Inserts line just before the forged-reply leg's last line (the shell's turn ending)."""
    def fn(entries):
        last = max(i for i, e in enumerate(entries) if e[1])
        entries.insert(last, [line, True])
    return audit_edit(fn)


def drop_event(event, turn_id=None):
    return audit_edit(lambda entries: entries.__setitem__(
        slice(None), [e for e in entries if not matches(e, event, turn_id)]))


def set_field(event, turn_id, key, value):
    def fn(entries):
        for e in entries:
            if matches(e, event, turn_id):
                e[0][key] = value
    return audit_edit(fn)


def reply(owner_text, new_reply):
    def fn(t):
        tr, _ = parse(t.splitlines())
        tid = next(u["turn_id"] for u in tr if u["role"] == "owner" and u["text"] == owner_text)
        for u in tr:
            if u["turn_id"] == tid and u["role"] == "vos":
                u["text"] = new_reply
        return "".join(json.dumps(u) + "\n" for u in tr)
    return edit("transcripts.jsonl", fn)


def rec_log_edit(fn):
    """fn(events) edits the event lines of the dump's recording log (a list of str), in place."""
    def apply(t):
        lines = t.split("\n")
        start = next(i for i, line in enumerate(lines) if REC_LOG_TITLE.fullmatch(line)) + 1
        end = next(i for i in range(start, len(lines)) if not lines[i].strip())
        lines[start:end] = fn(lines[start:end])
        return "\n".join(lines)
    return edit("recording.txt", apply)


def rec_line(stamp, pack=ACTUATOR, uid=UID):
    line = f"{stamp}:250 rec start riid:7 uid:{uid} session:11 src:VOICE_COMMUNICATION not silenced"
    return rec_log_edit(lambda ev: ev + [line + (f" pack:{pack}" if pack else "")])


def full_rec_log(minute, second=0):
    """The recording log full: REC_LOG_CAPACITY events of another app's, one a second from minute
    (MM-DD HH:MM) and second on."""
    return rec_log_edit(lambda ev: [
        f"{minute}:{s:02d}:000 rec {'start' if s % 2 == 0 else 'stop'} riid:{100 + s // 2} uid:10111 "
        f"session:{20 + s // 2} src:MIC{' not silenced' if s % 2 == 0 else ''} pack:com.example.recorder"
        for s in range(second, second + REC_LOG_CAPACITY)])


def dump_edit(old, new):
    return edit("recording.txt", lambda t: t.replace(old, new))


REC_TITLE = "Events log: recording activity received by AudioService"
# The section that only the old self-test's fixture had (#333): a dump with this and no recording log.
FABRICATED_HEADING = "### Recording Activity"
# What a sed range from the monitor's header to the first blank line keeps: no events, and no title.
MONITOR_ONLY = "RecordActivityMonitor dump time: 12:02:30 PM\nriid 1; active false\n"
# Variants of the passing run's dump that real devices print: every check must still PASS.
VARIANTS = [  # (what, how)
    ("the recording log's title as before Android 14 (\"Audio event log: \")",
     dump_edit(REC_TITLE, "Audio event log: recording activity received by AudioService")),
    ("the recording log's title with an older tag",
     dump_edit(REC_TITLE, "Audio event log: recording activity as reported through "
                          "AudioSystem.dispatchRecordingConfigChange")),
    ("no recording since boot: the log has its title and no event", rec_log_edit(lambda ev: [])),
    ("the recording log ends the dump (no blank line after it)",
     edit("recording.txt", lambda t: t[:t.index("\nAudioDeviceBroker:")])),
    ("the recording log is full, from before the typed legs", full_rec_log("10-31 11:59")),
]
DUMPSYS_TIMEOUT = "*** SERVICE 'audio' DUMP TIMEOUT (10000ms) EXPIRED ***"


SUMMON_TURN = f"10-31 12:01:00.700 100 103 I VOS_TURN: turn opened in session turnId={VOICE_ID} after_ms=90\n"
STOP_ID = IDS[STOP_TURN]
BREAKS = [  # (check id, what is broken, how)
    ("voice_ended", "the voice turn never ended", edit("voice.txt", lambda t: t.replace(" over after_ms=", " open"))),
    ("typed_answered", "a typed turn ended at its deadline",
     set_field("typed.turn_ended", IDS[2], "reason", "session: typed turn deadline")),
    ("typed_answered", "only seven typed turns opened", drop_event("typed.turn_opened", IDS[5])),
    ("typed_answered", "the turn after the stop ended at its deadline",
     set_field("typed.turn_ended", IDS[7], "reason", "session: typed turn deadline")),
    ("time_reply", "the time reply has no time", reply("What time is it?", "I can't tell.")),
    ("media_control", "media_control never ran", drop_event("tool.call", IDS[1])),
    ("media_control", "media_control ran for another turn", set_field("tool.call", IDS[1], "turnId", IDS[0])),
    ("followup_dana", "the follow-up does not name Dana",
     reply("What did I tell you my sister is called?", "I don't know.")),
    ("direct_reply", "the direct-reply turn came from the window",
     set_field("typed.turn_opened", IDS[3], "source", "debug_window")),
    ("direct_reply", "the direct-reply turn got no reply", reply("What time is it now?", " ")),
    ("rec_typed", "the recording log is missing", remove("recording.txt")),
    ("rec_typed", "the actuator recorded during the typed turns", rec_line("10-31 12:00:20")),
    ("rec_typed", "the actuator recorded, logged by uid with no package name", rec_line("10-31 12:00:20", pack=None)),
    ("rec_typed", "no start time for the typed legs", fact("typed_from", "")),
    ("rec_typed", "dumpsys audio printed nothing", edit("recording.txt", lambda t: "")),
    ("rec_typed", "the dump has the old self-test's \"### Recording Activity\" heading, not the recording log",
     dump_edit(REC_TITLE, FABRICATED_HEADING)),
    ("rec_typed", "only the monitor's header and recorders were kept (a sed range to the first blank line)",
     edit("recording.txt", lambda t: MONITOR_ONLY)),
    ("rec_typed", "dumpsys timed out inside the recording log", rec_log_edit(lambda ev: ev[:2] + [DUMPSYS_TIMEOUT])),
    ("rec_typed", "a line of the recording log is cut short", rec_log_edit(lambda ev: ev + ["10-31 12:00:2"])),
    ("rec_typed", "two recording logs in the dump", edit("recording.txt", lambda t: t + "\n" + REC_TITLE + "\n")),
    ("rec_typed", "the recording log is full from the typed legs' start (it may have dropped their events)",
     full_rec_log("10-31 12:00", 10)),
    ("forged_refused", "the forged reply was not refused (accepted)", drop_event("typed.reply_refused")),
    ("forged_refused", "the only refusal is for a turn the actuator opened (a late reply), not the forgery",
     set_field("typed.reply_refused", None, "turnId", IDS[3])),
    ("forged_refused", "the forged leg's bounds are missing", fact("forge_audit_to", "")),
    ("forged_no_turn", "the actuator opened a turn for the forged reply",
     in_forge_leg({"event": "typed.turn_opened", "turnId": "44444444-4444-4444-8444-444444444444",
                   "source": "session", "chars": 26})),
    ("forged_no_turn", "a voice turn started in the forged leg",
     in_forge_leg({"event": "audio.transition", "state": "CAPTURE", "turnId": VOICE_ID})),
    ("forged_no_turn", "a tool ran for the forged turn id",
     in_forge_leg({"event": "tool.call", "turnId": FORGED, "typed": True, "tool": "media_control"})),
    ("forged_no_turn", "the forged id is among the turns the run opened",
     set_field("typed.turn_opened", IDS[0], "turnId", FORGED)),
    ("forged_no_turn", "an unreadable audit line in the forged leg",
     in_forge_leg('{"event":"typed.turn_opened","turnId":"5555')),
    ("summon_during", "the typed turn was not yet open in the session", fact("in_session", "0")),
    ("summon_during", "the typed turn had ended before the summon",
     edit("summon-typed.txt", lambda t: t + "10-31 12:01:00.400 100 102 I VOS_TYPED: typed turn ended turnId=x\n")),
    ("summon_refused", "the summon was not refused", edit("summon.txt", lambda t: "")),
    ("summon_no_turn", "the summon opened a voice turn", edit("summon.txt", lambda t: t + SUMMON_TURN)),
    ("summon_no_turn", "the summon started the live mic",
     edit("summon.txt", lambda t: t + "10-31 12:01:00.800 100 104 I VOS_TURN: capture turnId=x source=VOICE_COMMUNICATION\n")),
    ("summon_no_turn", "the voice-start lines no longer match a voice turn (the voice leg shows none)",
     edit("voice.txt", lambda t: VOICE_START.sub("turn bound", t))),
    ("rec_summon", "the recording log is missing", remove("recording.txt")),
    ("rec_summon", "the actuator recorded at the summon", rec_line("10-31 12:01:01")),
    ("rec_summon", "no start time for the summon", fact("summon_from", "12:01")),
    ("rec_summon", "the dump has the old self-test's \"### Recording Activity\" heading, not the recording log",
     dump_edit(REC_TITLE, FABRICATED_HEADING)),
    ("rec_summon", "dumpsys timed out inside the recording log", rec_log_edit(lambda ev: ev[:2] + [DUMPSYS_TIMEOUT])),
    ("rec_summon", "the recording log is full from the summon (it may have dropped its events)",
     full_rec_log("10-31 12:01")),
    ("stop_during", "the shell did not press the tile (the turn had already ended)", fact("stop_pressed", "0")),
    ("stop_during", "the typed turn was not yet open in the session at the press", fact("stop_in_session", "0")),
    ("stop_during", "the actuator stopped the turn before the session had it",
     set_field("typed.turn_stop", STOP_ID, "in_session", False)),
    ("stop_no_voice", "the tile press opened a voice turn",
     edit("stop.txt", lambda t: t + SUMMON_TURN.replace("12:01:00.700", "12:01:21.300"))),
    ("stop_no_voice", "the tile press started the live mic",
     edit("stop.txt", lambda t: t + "10-31 12:01:21.200 100 104 I VOS_TURN: capture turnId=x source=MIC\n")),
    ("stop_no_voice", "the shell did not press the tile", fact("stop_pressed", "0")),
    ("stop_no_voice", "the stop leg's log is missing (nothing shows the press)", remove("stop.txt")),
    ("stop_no_voice", "the voice-start lines no longer match a voice turn (the voice leg shows none)",
     edit("voice.txt", lambda t: VOICE_START.sub("turn bound", t))),
    ("stop_owner", "the press did not stop the turn: it was answered",
     set_field("typed.turn_ended", STOP_ID, "reason", "session: typed reply")),
    ("stop_owner", "the stop came from another source than the tile",
     set_field("typed.turn_ended", STOP_ID, "reason", "owner stop: window")),
    ("stop_owner", "no typed.turn_stop line for the stopped turn", drop_event("typed.turn_stop")),
    ("stop_owner", "two stops", audit_edit(lambda entries: entries.append(
        [{"event": "typed.turn_stop", "turnId": IDS[7], "source": "debug_tile", "in_session": True}, False]))),
    ("stop_fast", "the stopped turn ended 900 ms after the stop",
     edit("stop.txt", lambda t: t.replace("t_up=500040", "t_up=500900"))),
    ("stop_fast", "the stop's log lines are missing", edit("stop.txt", lambda t: "")),
    ("stop_session_end", "the stop backstop ended it, not the session's endTurn",
     set_field("typed.turn_ended", STOP_ID, "ended_by", "stop backstop: no endTurn from the session in 5000 ms")),
    ("stop_session_end", "no stopped turn", set_field("typed.turn_ended", STOP_ID, "reason", "session: typed reply")),
    ("stop_no_late", "the session's reply for the stopped turn reached the actuator (refused)",
     audit_edit(lambda entries: entries.append(
         [{"event": "typed.reply_refused", "turnId": STOP_ID, "chars": 40, "stopped": True}, False]))),
    ("stop_no_late", "a tool call for the stopped turn reached the actuator",
     audit_edit(lambda entries: entries.append(
         [{"event": "tool.call", "turnId": STOP_ID, "typed": True, "stopped": True, "tool": "time_get",
           "status": "FAILED"}, False]))),
    ("stop_no_late", "the session reported a model call for the stopped turn",
     audit_edit(lambda entries: entries.append([{"event": "typed.model_call_refused", "turnId": STOP_ID}, False]))),
    ("stop_no_late", "no stopped turn to judge", set_field("typed.turn_ended", STOP_ID, "reason", "session: typed reply")),
    ("stop_no_vos", "the stopped turn wrote a VOS line",
     edit("transcripts.jsonl", lambda t: t + json.dumps({"turn_id": STOP_ID, "role": "vos", "text": "One, two"}) + "\n")),
    ("stop_no_vos", "no stopped turn to judge", set_field("typed.turn_ended", STOP_ID, "reason", "session: typed reply")),
    ("after_stop", "the turn after the stop got no reply", reply("Thanks, that is all.", "")),
]


def self_test():
    root = Path(tempfile.mkdtemp(prefix="chat-checks-"))
    try:
        base = root / "passing"
        base.mkdir()
        write_passing(base)
        _, results = evaluate(base)
        failed = [text for _, ok, text in results if not ok]
        if failed:
            print("self-test FAIL: the passing run fails: " + "; ".join(failed), file=sys.stderr)
            return 1
        ids = [cid for cid, _, _ in results]
        untested = sorted(set(ids) - {cid for cid, _, _ in BREAKS})
        if untested:
            print("self-test FAIL: no break for " + ", ".join(untested), file=sys.stderr)
            return 1
        bad = 0
        for n, (what, how) in enumerate(VARIANTS):  # dumps real devices print: still a passing run (#333)
            d = root / f"variant-{n}"
            shutil.copytree(base, d)
            how(d)
            _, results = evaluate(d)
            failed = [text for _, ok, text in results if not ok]
            if failed:
                bad += 1
                print(f"self-test FAIL: {what}: the passing run fails: " + "; ".join(failed), file=sys.stderr)
            else:
                print(f"variant: {what}: PASS")
        for n, (cid, what, how) in enumerate(BREAKS):
            d = root / f"break-{n}"
            shutil.copytree(base, d)
            how(d)
            _, results = evaluate(d)
            got = {c: (ok, text) for c, ok, text in results}
            ok, text = got[cid]
            others = [t for c, (o, t) in got.items() if not o and c != cid]
            if ok:
                bad += 1
                print(f"self-test FAIL: {what}: still PASS {text}", file=sys.stderr)
            else:
                print(f"{cid}: {what}: FAIL {text}" + (f" (also failing: {'; '.join(others)})" if others else ""))
        # The run files (#305): the passing run misses none, an empty RUN_DIR misses every one, and a
        # RUN_DIR that does not exist is no run: exit 2, with no check run.
        if unread(base):
            bad += 1
            print("self-test FAIL: the passing run's files are reported missing or empty: "
                  + ", ".join(unread(base)), file=sys.stderr)
        empty = root / "empty"
        empty.mkdir()
        first = evaluate(empty)[0][0]
        if not all(f"{name} (missing)" in first for name in RUN_FILES):
            bad += 1
            print(f"self-test FAIL: an empty run directory does not name every file as missing: {first}",
                  file=sys.stderr)
        with contextlib.redirect_stdout(io.StringIO()) as o, contextlib.redirect_stderr(io.StringIO()) as e:
            rc = main(root / "no-such-run")
        if rc != 2 or o.getvalue() or "no such run directory" not in e.getvalue():
            bad += 1
            print(f"self-test FAIL: a missing run directory gave exit {rc}, not 2 with \"no such run directory\"",
                  file=sys.stderr)
        if bad:
            return 1
        print(f"self-test PASS: the {len(ids)} checks pass on the passing run and its {len(VARIANTS)} variants, "
              f"and each fails on its breaks ({len(BREAKS)} in all); an empty run directory names all "
              f"{len(RUN_FILES)} run files as missing, and a missing one exits 2")
        return 0
    finally:
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if len(sys.argv) != 2 or sys.argv[1].startswith("-"):
        print("usage: chat_checks.py RUN_DIR | --self-test", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
