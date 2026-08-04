#!/usr/bin/env python3
"""Marionette M3.2 demo (protocol v1): camera modes and smoothing.

Looks at five world points around the player with mode "smooth" —
producing the D5 experiment's pan footage — then shows the contrast
cases: a double-speed pan, an instant snap, and a burst of deltas.
Convergence is detected from the observation stream (rotation stops
changing); wall-clock pan times print as evidence.

Run with the mod's logging verbosity at VERBOSE to also produce the
per-frame pan log that scripts/analyze_pan.py checks.

Requires:  pip install websockets
Usage:     python look_points.py [port]   (default 24680)
"""
import asyncio
import json
import math
import sys
import time

import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680
EYE_HEIGHT = 1.62
SETTLE_EPSILON = 0.01   # deg between observations = converged
SETTLE_TICKS = 3        # consecutive stable observations required
PAN_TIMEOUT = 10.0      # seconds before a pan counts as failed


async def next_observation(ws):
    while True:
        message = json.loads(await ws.recv())
        if message.get("type") == "observation":
            return message
        print("non-observation message:", message)


async def send(ws, **fields):
    await ws.send(json.dumps(fields))


async def sync(ws):
    """Drain any observations queued up unread and return the freshest one.

    Nothing reads the socket during an asyncio.sleep(), so frames the mod
    sent in the meantime pile up unread; a plain next_observation() right
    after would hand back the oldest of those — stale, from before the
    sleep — instead of the current state. The mod pushes a fresh frame
    every tick (~50 ms) for as long as the world is loaded, so "keep
    reading until nothing arrives" never settles — there's always another
    real one on the way. Instead, use a timeout far shorter than a tick:
    an already-buffered frame comes back in well under a millisecond,
    while one we'd have to wait for the next tick for does not, so a
    short timeout reliably tells "backlog" from "caught up".
    """
    latest = await next_observation(ws)
    while True:
        try:
            message = json.loads(await asyncio.wait_for(ws.recv(), timeout=0.01))
        except (asyncio.TimeoutError, TimeoutError):
            return latest
        if message.get("type") == "observation":
            latest = message


async def wait_converged(ws):
    """Wait until rotation is stable across observations; returns (obs, seconds)."""
    start = time.monotonic()
    last = await sync(ws)
    stable = 0
    while stable < SETTLE_TICKS:
        if time.monotonic() - start > PAN_TIMEOUT:
            raise RuntimeError("pan did not converge within %.0f s" % PAN_TIMEOUT)
        obs = await next_observation(ws)
        if (abs(obs["yaw"] - last["yaw"]) <= SETTLE_EPSILON
                and abs(obs["pitch"] - last["pitch"]) <= SETTLE_EPSILON):
            stable += 1
        else:
            stable = 0
        last = obs
    return last, time.monotonic() - start


async def main():
    async with websockets.connect(f"ws://127.0.0.1:{PORT}/") as ws:
        await send(ws, type="hello", versions=[1])
        reply = json.loads(await ws.recv())
        assert reply["type"] == "hello", reply
        if not reply["capabilities"].get("camera"):
            sys.exit("mod does not advertise the camera capability")

        obs = await sync(ws)
        x, y, z = obs["x"], obs["y"], obs["z"]
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
            await send(ws, type="look", mode="smooth", x=px, y=py, z=pz)
            obs, took = await wait_converged(ws)
            print(f"  point {i + 1}: converged at yaw={obs['yaw']:.1f} "
                  f"pitch={obs['pitch']:.1f} in {took:.2f}s")

        print("speed multiplier:")
        await send(ws, type="look", mode="smooth", yaw=obs["yaw"] + 90.0, pitch=0.0)
        _, slow = await wait_converged(ws)
        await send(ws, type="look", mode="smooth", yaw=obs["yaw"], pitch=0.0, speed=2.0)
        _, fast = await wait_converged(ws)
        print(f"  90-degree pan: speed 1.0 -> {slow:.2f}s, speed 2.0 -> {fast:.2f}s")
        assert fast < slow, "speed 2.0 was not faster"

        print("instant mode still snaps:")
        before = await sync(ws)
        await send(ws, type="look", yaw=before["yaw"] + 120.0, pitch=0.0)
        after, took = await wait_converged(ws)
        print(f"  snapped {after['yaw'] - before['yaw']:+.1f} degrees in {took:.2f}s")

        print("delta mode:")
        before = await sync(ws)
        for _ in range(4):
            await send(ws, type="look", mode="delta", yaw=15.0, pitch=0.0)
        after, _ = await wait_converged(ws)
        print(f"  four +15 deltas moved yaw {after['yaw'] - before['yaw']:+.1f} degrees")

        await send(ws, type="release")
        print("done")


if __name__ == "__main__":
    asyncio.run(main())
