#!/usr/bin/env python3
"""D5 numeric pass-criteria check over Marionette's VERBOSE pan log.

Parses "Pan start target ...", "Pan yaw=... pitch=... t=...", and
"Pan converged t=..." lines (emitted by MarionetteClient at VERBOSE
verbosity) and checks, per pan:

  no-snap:     no frame step exceeding 2 x speed x dt + 0.2 deg
               (the spring's peak velocity slightly exceeds the
               characteristic speed; 2x bounds all three candidates)
  no-overshot: never beyond the target by more than 0.5 deg on either axis
  convergence: settled within 1500 ms x (180 / speed), final error
               <= 0.1 deg on both axes

Point-target pans assume a stationary player (the look_points.py
experiment setup), so the logged start target stays valid.

Usage: python analyze_pan.py <logfile e.g. run/logs/latest.log>
"""
import math
import re
import sys

START = re.compile(r"Pan start target yaw=(-?[\d.]+) pitch=(-?[\d.]+) speed=([\d.]+) model=(\w+)")
FRAME = re.compile(r"Pan yaw=(-?[\d.]+) pitch=(-?[\d.]+) t=([\d.]+)")
END = re.compile(r"Pan converged t=([\d.]+)")

SNAP_SLACK = 0.2          # deg absolute slack on the per-frame step bound
OVERSHOOT_LIMIT = 0.5     # deg
FINAL_ERROR_LIMIT = 0.1   # deg
BUDGET_MS_AT_180 = 1500.0


def analyze(path):
    pans, current = [], None
    for line in open(path, encoding="utf-8", errors="replace"):
        if m := START.search(line):
            current = {"target": (float(m[1]), float(m[2])), "speed": float(m[3]),
                       "model": m[4], "frames": [], "converged_ms": None}
            pans.append(current)
        elif current and (m := FRAME.search(line)):
            current["frames"].append((float(m[1]), float(m[2]), float(m[3])))
        elif current and (m := END.search(line)):
            current["converged_ms"] = float(m[1])
            current = None

    failures = 0
    for i, pan in enumerate(pans):
        label = f"pan {i + 1} ({pan['model']}, speed {pan['speed']:.0f})"
        problems = []
        ty, tp = pan["target"]
        frames = pan["frames"]
        if pan["converged_ms"] is None:
            print(f"{label}: SKIP (never converged — canceled or log truncated)")
            continue
        if not frames:
            print(f"{label}: SKIP (no frame lines — was verbosity VERBOSE?)")
            continue
        for (y1, p1, t1), (y2, p2, t2) in zip(frames, frames[1:]):
            dt = (t2 - t1) / 1000.0
            step = math.hypot(y2 - y1, p2 - p1)
            if dt > 0 and step > 2.0 * pan["speed"] * dt + SNAP_SLACK:
                problems.append(f"snap: {step:.2f} deg in {dt * 1000:.1f} ms at t={t2:.0f}")
                break
        sy = frames[0][0] - ty  # start-to-target sign, per axis
        sp = frames[0][1] - tp
        for y, p, t in frames:
            if sy != 0 and (y - ty) * math.copysign(1, sy) < -OVERSHOOT_LIMIT:
                problems.append(f"yaw overshoot at t={t:.0f}: {y:.2f} past {ty:.2f}")
                break
            if sp != 0 and (p - tp) * math.copysign(1, sp) < -OVERSHOOT_LIMIT:
                problems.append(f"pitch overshoot at t={t:.0f}: {p:.2f} past {tp:.2f}")
                break
        budget = BUDGET_MS_AT_180 * (180.0 / pan["speed"])
        if pan["converged_ms"] > budget:
            problems.append(f"slow: {pan['converged_ms']:.0f} ms > {budget:.0f} ms budget")
        fy, fp = frames[-1][0], frames[-1][1]
        # The final frame precedes the exact-target convergence write; both
        # must already be inside the convergence epsilon's neighborhood.
        if abs(fy - ty) > FINAL_ERROR_LIMIT or abs(fp - tp) > FINAL_ERROR_LIMIT:
            problems.append(f"final error yaw={abs(fy - ty):.3f} pitch={abs(fp - tp):.3f}")
        if problems:
            failures += 1
            print(f"{label}: FAIL — " + "; ".join(problems))
        else:
            print(f"{label}: PASS ({pan['converged_ms']:.0f} ms, {len(frames)} frames)")

    print(f"\n{len(pans)} pans, {failures} failed")
    return failures


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(1 if analyze(sys.argv[1]) else 0)
