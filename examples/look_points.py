#!/usr/bin/env python3
"""Marionette M3.2 demo (protocol v2): camera modes and smoothing.

Looks at five world points around the player with mode "smooth" —
producing the D5 experiment's pan footage — then shows the contrast
cases: a double-speed pan, an instant snap, and a burst of deltas.
Convergence is detected from the observation stream (rotation stops
changing); wall-clock pan times print as evidence.

Run with the mod's logging verbosity at VERBOSE to also produce the
per-frame pan log that scripts/analyze_pan.py checks.

Requires:  pip install "marionette-mc==0.1.0a1"
Usage:     python look_points.py [port]   (default 24680)
"""
import asyncio
import math
import sys
import time

from _common import connect

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680
EYE_HEIGHT = 1.62
SETTLE_EPSILON = 0.01   # deg between observations = converged
SETTLE_TICKS = 3        # consecutive stable observations required
PAN_TIMEOUT = 10.0      # seconds before a pan counts as failed


async def next_observation(ws):
    return await ws.next_observation(timeout=10)


async def sync(ws):
    """The package retains only the latest unread observation."""
    return await ws.next_observation(timeout=10)


async def wait_converged(ws):
    """Wait until rotation is stable across observations; returns (obs, seconds)."""
    start = time.monotonic()
    last = await sync(ws)
    stable = 0
    while stable < SETTLE_TICKS:
        if time.monotonic() - start > PAN_TIMEOUT:
            raise RuntimeError("pan did not converge within %.0f s" % PAN_TIMEOUT)
        obs = await next_observation(ws)
        if (abs(obs["player"]["yaw"] - last["player"]["yaw"]) <= SETTLE_EPSILON
                and abs(obs["player"]["pitch"] - last["player"]["pitch"]) <= SETTLE_EPSILON):
            stable += 1
        else:
            stable = 0
        last = obs
    return last, time.monotonic() - start


async def main():
    async with connect(f"ws://127.0.0.1:{PORT}/", role="controller", sections=["player"], required_capabilities=["camera"]) as ws:
        reply = ws.hello
        assert reply["type"] == "hello", reply
        if not reply["capabilities"].get("camera"):
            sys.exit("mod does not advertise the camera capability")

        obs = await sync(ws)
        x, y, z = obs["player"]["x"], obs["player"]["y"], obs["player"]["z"]
        eye = y + EYE_HEIGHT
        # Five points around the player: E, NE-high, N, W-low, S.
        points = [
            (x + 12, eye,     z),
            (x + 8,  eye + 6, z - 8),
            (x,      eye,     z - 12),
            (x - 12, eye - 4, z),
            (x,      eye,     z + 12),
        ]
        print("five-point smoothed pan sequence:")
        for i, (px, py, pz) in enumerate(points):
            await ws.look(mode="smooth", x=px, y=py, z=pz)
            obs, took = await wait_converged(ws)
            print(f"  point {i + 1}: converged at yaw={obs['player']['yaw']:.1f} "
                  f"pitch={obs['player']['pitch']:.1f} in {took:.2f}s")

        print("speed multiplier:")
        await ws.look(mode="smooth", yaw=obs["player"]["yaw"] + 90.0, pitch=0.0)
        _, slow = await wait_converged(ws)
        await ws.look(mode="smooth", yaw=obs["player"]["yaw"], pitch=0.0, speed=2.0)
        _, fast = await wait_converged(ws)
        print(f"  90-degree pan: speed 1.0 -> {slow:.2f}s, speed 2.0 -> {fast:.2f}s")
        assert fast < slow, "speed 2.0 was not faster"

        print("instant mode still snaps:")
        before = await sync(ws)
        await ws.look(yaw=before["player"]["yaw"] + 120.0, pitch=0.0)
        after, took = await wait_converged(ws)
        print(f"  snapped {after['player']['yaw'] - before['player']['yaw']:+.1f} degrees in {took:.2f}s")

        print("delta mode:")
        before = await sync(ws)
        for _ in range(4):
            await ws.look(mode="delta", yaw=15.0, pitch=0.0)
        after, _ = await wait_converged(ws)
        print(f"  four +15 deltas moved yaw {after['player']['yaw'] - before['player']['yaw']:+.1f} degrees")

        await ws.release()
        print("done")


if __name__ == "__main__":
    asyncio.run(asyncio.wait_for(main(), timeout=120))
