"""Echo level of one echo-barge turn, from the actuator's debug "mic level" lines (#249, #265).

Prints: the median mic dBFS in seconds while VOS plays (>= 0.8 s of reply in that second), the median
with nothing playing (the room), the difference, and the convergence time: seconds of reply until the
first playing second within 3 dB of the room ("never" if none). "?" where there is no data.

Usage: python3 -I eval/m1/echo_levels.py TURN_LOG
"""
import re
import statistics
import sys

LEVEL = re.compile(r"mic level .* dbfs=(-?[0-9.]+) played_bytes=([0-9]+)")
PLAYING = 25600  # 0.8 s of 16 kHz 16-bit reply in that second


def main(path):
    seconds = []  # (dbfs, played bytes), one per second, in order
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LEVEL.search(line)
            if m:
                seconds.append((float(m.group(1)), int(m.group(2))))
    play = [db for db, b in seconds if b >= PLAYING]
    room = [db for db, b in seconds if b == 0]
    e = statistics.median(play) if play else None
    r = statistics.median(room) if room else None
    conv = "?"
    if play and r is not None:
        conv = "never"
        for k, db in enumerate(play):
            if db <= r + 3:
                conv = str(k + 1)
                break
    fmt = lambda v: "?" if v is None else f"{v:.1f}"
    print(fmt(e), fmt(r), fmt(e - r) if e is not None and r is not None else "?", conv)


if __name__ == "__main__":
    main(sys.argv[1])
