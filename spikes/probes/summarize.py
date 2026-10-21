#!/usr/bin/env python3
"""Summarize VOS_S07 / VOS_S08 probe lines from `adb logcat -d -s VOS_S07` (or VOS_S08) output.

Usage: python3 -I spikes/probes/summarize.py LOG [LOG ...]   (or pipe logcat output on stdin)
"""
import math
import re
import sys
from collections import defaultdict

KV = re.compile(r"(\w+)=(\S*)")
S07_P95_MS = 150.0
S08_P95_MS = 10.0


def fields(line, marker):
    i = line.find(marker)
    return dict(KV.findall(line[i + len(marker):])) if i >= 0 else None


def pct(sorted_vals, p):
    """Nearest-rank percentile; None when empty."""
    if not sorted_vals:
        return None
    idx = max(0, min(len(sorted_vals) - 1, math.ceil(p / 100 * len(sorted_vals)) - 1))
    return sorted_vals[idx]


def fmt(v):
    return "-" if v is None else f"{v:g}"


def steps_table(trials):
    """#261: p50/p95 of each step a variant logged (ms from the summon; max_gap and zero_run are durations)."""
    keyed = {k: rows for k, rows in trials.items() if any(n.endswith("_ms") and n != "latency_ms" for t in rows for n in t)}
    if not keyed:
        return
    print("steps (p50/p95 ms from the summon; durations instead: max_gap = longest wait between reads, "
          "zero_run = longest silence after the first sound, short = time passed minus audio delivered between the first and last read)")
    for key in sorted(keyed):
        rows = [t for t in keyed[key] if t.get("ok") == "true"]
        names = []
        for t in rows:
            for n in t:
                if n.endswith("_ms") and n != "latency_ms" and n not in names:
                    names.append(n)
        cells = []
        for n in names:
            vals = sorted(float(t[n]) for t in rows if n in t)
            cells.append(f"{n[:-3]} {fmt(pct(vals, 50))}/{fmt(pct(vals, 95))}")
        routed = sorted({t.get("routed", "?") for t in rows})
        print(f"  {key[1]:15} {key[2]:6} n={len(rows)}: " + ", ".join(cells) + f"; routed device type {'/'.join(routed)}")


def main(paths):
    lines = []
    if paths:
        for p in paths:
            with open(p, encoding="utf-8", errors="replace") as f:
                lines += f.readlines()
    else:
        lines = sys.stdin.readlines()

    trials = defaultdict(list)
    errors = defaultdict(lambda: defaultdict(int))
    s08_results, s08_done = [], []
    for line in lines:
        t = fields(line, "VOS_S07 trial")
        if t is not None:
            key = (t.get("source", "?"), t.get("variant", "rec"), t.get("locked", "?"))
            trials[key].append(t)
            if t.get("ok") != "true":
                errors[key][t.get("err", "?")] += 1
            continue
        r = fields(line, "VOS_S08 result")
        if r is not None:
            s08_results.append(r)
            continue
        d = fields(line, "VOS_S08 actuator done")
        if d is not None:
            s08_done.append(d)

    if trials:
        print(f"S-07 (latency = handler -> first non-zero mic buffer; pass = all ok and p95 <= {S07_P95_MS:g} ms)")
        print(f"{'source':8} {'variant':15} {'locked':6} {'n':>3} {'ok':>3} {'p50_ms':>7} {'p95_ms':>7} {'max_ms':>7}  pass")
        for key in sorted(trials):
            rows = trials[key]
            lat = sorted(float(t["latency_ms"]) for t in rows if t.get("ok") == "true")
            p95 = pct(lat, 95)
            ok = len(lat) == len(rows) and p95 is not None and p95 <= S07_P95_MS
            print(f"{key[0]:8} {key[1]:15} {key[2]:6} {len(rows):>3} {len(lat):>3} {fmt(pct(lat, 50)):>7} {fmt(p95):>7} "
                  f"{fmt(lat[-1] if lat else None):>7}  {'PASS' if ok else 'FAIL'}")
            for err, count in sorted(errors[key].items()):
                print(f"    {count} x {err}")
        steps_table(trials)

    for r in s08_results:
        try:
            ok = (float(r["p95_ms"]) <= S08_P95_MS and int(r["underruns"]) == 0
                  and r.get("err", "none") == "none")
        except (KeyError, ValueError):
            ok = False
        print(f"S-08 pipe: frames={r.get('frames')} p50_ms={r.get('p50_ms')} p95_ms={r.get('p95_ms')} "
              f"max_ms={r.get('max_ms')} underruns={r.get('underruns')} gaps={r.get('gaps')} "
              f"err={r.get('err', 'none')}  {'PASS' if ok else 'FAIL'} (p95 <= {S08_P95_MS:g} ms, 0 underruns)")
    for d in s08_done:
        print(f"S-08 actuator: frames={d.get('frames')} zero_frames={d.get('zero_frames')} "
              f"fgs={d.get('fgs')} err={d.get('err')}")

    if not trials and not s08_results and not s08_done:
        print("no VOS_S07 / VOS_S08 lines found", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
