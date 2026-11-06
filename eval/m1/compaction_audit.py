"""The counts-only rule for the compaction audit lines of eval/m1/context-budget.sh (M1-CTX-01, #272; #334).

A context.compact or context.compact_failed audit line carries counts and labels, never conversation
text: only the keys in COUNT_KEYS, and the audit chain's prev (M2-AUDIT-01, #154, #327), which must be
a lowercase hex SHA-256 (the first record's is 64 zeros). A line with no prev, written before the
chain existed, still passes: this rule judges content, and eval/m2/audit-01.sh judges the chain.

Usage: python3 -I eval/m1/compaction_audit.py --self-test   (no device)
"""
import hashlib
import json
import re
import sys

COUNT_KEYS = frozenset({"ts", "event", "context", "tokens_before", "tokens_after", "budget", "compacted_owner",
                        "compacted_vos", "compacted_tool_results", "compacted_notes", "model", "taint", "error",
                        "source"})
CHAIN_PREV = re.compile(r"[0-9a-f]{64}")


def not_counts(line):
    """Why an audit line (a dict) is not counts only: each key it should not have, and "prev:malformed"
    if its prev is not a lowercase hex SHA-256. Empty when it is counts only."""
    bad = sorted(str(k) for k in line if k not in COUNT_KEYS and k != "prev")
    if "prev" in line and not (isinstance(line["prev"], str) and CHAIN_PREV.fullmatch(line["prev"])):
        bad.append("prev:malformed")
    return bad


def problems(lines):
    """not_counts over lines, each reason once, in order."""
    return list(dict.fromkeys(p for line in lines for p in not_counts(line)))


# --- self-test: lines that must pass, and lines that must not ---

def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


COMPACT = {"ts": 1793952509774, "event": "context.compact", "context": "conversation", "tokens_before": 460,
           "tokens_after": 381, "budget": 450, "compacted_owner": 6, "compacted_vos": 7, "compacted_tool_results": 1,
           "compacted_notes": 0, "model": "xai:grok-4.20-0309-non-reasoning", "taint": "owner+app",
           "prev": sha("the line before")}
FAILED = {"ts": 1793952509774, "event": "context.compact_failed", "context": "conversation", "tokens_before": 460,
          "tokens_after": 460, "budget": 450, "compacted_owner": 6, "compacted_vos": 7, "compacted_tool_results": 1,
          "compacted_notes": 0, "model": "xai:grok-4.20-0309-non-reasoning", "taint": "owner+app",
          "error": "SocketTimeoutException", "prev": sha("the line before")}


def without(line, key):
    return {k: v for k, v in line.items() if k != key}


def self_test():
    good, bad = [], []  # (what, line)
    for base in (COMPACT, FAILED):
        e = base["event"]
        good += [
            (f"{e} with a chained prev", base),
            (f"{e} whose prev is genesis (the first record)", {**base, "prev": "0" * 64}),
            (f"{e} with no prev (legacy: before the chain)", without(base, "prev")),
            (f"{e} with only ts and event", {"ts": base["ts"], "event": e}),
        ]
        bad += [
            (f"{e} with the summary's text", {**base, "summary": "The owner's sister is Dana."}),
            (f"{e} with the owner's words", {**without(base, "prev"), "text": "My sister's name is Dana."}),
            (f"{e} with a key next to prev", {**base, "prev_line": "My sister's name is Dana."}),
            (f"{e} with prev in upper case", {**base, "prev": base["prev"].upper()}),
            (f"{e} with a 63-digit prev", {**base, "prev": base["prev"][:63]}),
            (f"{e} with a 65-digit prev", {**base, "prev": base["prev"] + "0"}),
            (f"{e} with a non-hex digit in prev", {**base, "prev": "g" + base["prev"][1:]}),
            (f"{e} with a newline after prev", {**base, "prev": base["prev"] + "\n"}),
            (f"{e} with an empty prev", {**base, "prev": ""}),
            (f"{e} with a null prev", {**base, "prev": None}),
            (f"{e} with a number for prev", {**base, "prev": 0}),
            (f"{e} with text in prev", {**base, "prev": "My sister's name is Dana."}),
            (f"{e} with the chain hash and text in prev", {**base, "prev": [base["prev"], "Dana"]}),
        ]
    failures = 0
    for what, line in good:
        line = json.loads(json.dumps(line))  # as the summary reads it: parsed from a JSONL line
        if not_counts(line):
            failures += 1
            print(f"self-test FAIL: {what}: rejected ({', '.join(not_counts(line))})", file=sys.stderr)
    for what, line in bad:
        line = json.loads(json.dumps(line))
        if not not_counts(line):
            failures += 1
            print(f"self-test FAIL: {what}: accepted as counts only", file=sys.stderr)
        else:
            print(f"{what}: rejected ({', '.join(not_counts(line))})")
    # Over a run's lines: one bad line of either event is enough, and each reason is named once.
    mixed = [COMPACT, FAILED, {**FAILED, "summary": "Dana"}, {**COMPACT, "prev": "x"}, {**COMPACT, "prev": "y"}]
    if problems([COMPACT, FAILED, without(COMPACT, "prev")]) or problems(mixed) != ["summary", "prev:malformed"]:
        failures += 1
        print(f"self-test FAIL: problems over a run's lines: {problems(mixed)}", file=sys.stderr)
    if failures:
        return 1
    print(f"self-test PASS: {len(good)} counts-only lines accepted, {len(bad)} others rejected "
          "(context.compact and context.compact_failed)")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    print("usage: compaction_audit.py --self-test", file=sys.stderr)
    sys.exit(2)
