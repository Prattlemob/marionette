#!/usr/bin/env python3
"""Marionette M3.1 demo (protocol v1): the complete movement set.

Runs every held control and the marquee combinations, measuring
horizontal speed from observations so each mode is self-evident:
walk -> sprint (faster) -> sneak (slower), strafes and back, then a
single tap-jump vs a held bunny-hop, then a sprint-jump burst. Watch
the rendered client while it runs; speeds print as blocks/second.

The sneak-to-edge safety check stays manual: stand the player near a
drop, run with --sneak-edge, and the player must stop at the lip.

Requires:  pip install websockets
Usage:     python full_movement.py [port] [--sneak-edge]   (default 24680)
"""
import asyncio
import json
import math
import sys

import websockets

args = [a for a in sys.argv[1:] if a != "--sneak-edge"]
SNEAK_EDGE = "--sneak-edge" in sys.argv
PORT = int(args[0]) if args else 24680


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
        else:
            print("non-observation message:", message)


async def measure_speed(ws, seconds):
    """Horizontal blocks/second over roughly the next `seconds`."""
    start = await sync(ws)
    while True:
        end = await next_observation(ws)
        if end["tick"] - start["tick"] >= seconds * 20:
            break
    dist = math.dist((end["x"], end["z"]), (start["x"], start["z"]))
    return dist / ((end["tick"] - start["tick"]) / 20.0)


async def count_hops(ws, seconds):
    """Rising y edges (jump take-offs) over roughly the next `seconds`."""
    first = await sync(ws)
    base, hops, airborne = first["y"], 0, False
    while True:
        obs = await next_observation(ws)
        if obs["tick"] - first["tick"] >= seconds * 20:
            return hops
        up = obs["y"] > base + 0.01
        if up and not airborne:
            hops += 1
        airborne = up


async def main():
    async with websockets.connect(f"ws://127.0.0.1:{PORT}/") as ws:
        await send(ws, type="hello", versions=[1], role="controller")
        hello = json.loads(await ws.recv())
        assert hello.get("type") == "hello", f"handshake rejected: {hello}"
        assert hello.get("capabilities", {}).get("tap"), "mod lacks tap capability"

        # Pin the heading so every leg is comparable.
        start = await next_observation(ws)
        await send(ws, type="look", yaw=start["yaw"], pitch=0.0)

        if SNEAK_EDGE:
            print("sneak-edge: sneaking forward for 4 s — player must stop at the lip")
            await send(ws, type="input", forward=True, sneak=True)
            before = await next_observation(ws)
            await asyncio.sleep(4.0)
            after = await sync(ws)
            await send(ws, type="release")
            dropped = before["y"] - after["y"]
            print(f"y change: {dropped:.2f} over {after['tick'] - before['tick']} ticks (expect ~0: did not fall)")
            return

        print("walk:", end=" ", flush=True)
        await send(ws, type="input", forward=True)
        walk = await measure_speed(ws, 3.0)
        print(f"{walk:.2f} b/s")

        print("sprint:", end=" ", flush=True)
        await send(ws, type="input", sprint=True)
        sprint = await measure_speed(ws, 3.0)
        print(f"{sprint:.2f} b/s (expect ~1.3x walk)")

        print("sneak:", end=" ", flush=True)
        await send(ws, type="input", sprint=False, sneak=True)
        sneak = await measure_speed(ws, 3.0)
        print(f"{sneak:.2f} b/s (expect ~0.3x walk)")
        await send(ws, type="input", forward=False, sneak=False)
        await asyncio.sleep(0.5)  # settle

        for name, fields in (("strafe left", {"left": True}),
                             ("strafe right", {"right": True}),
                             ("back", {"back": True})):
            await send(ws, type="input", **fields)
            speed = await measure_speed(ws, 1.5)
            await send(ws, type="input", **{key: False for key in fields})
            print(f"{name}: {speed:.2f} b/s")
            await asyncio.sleep(0.5)

        print("tap jump:", end=" ", flush=True)
        await send(ws, type="input", tap=["jump"])
        print(f"{await count_hops(ws, 2.0)} hop(s) (expect exactly 1)")

        print("held jump:", end=" ", flush=True)
        await send(ws, type="input", jump=True)
        hops = await count_hops(ws, 2.0)
        await send(ws, type="input", jump=False)
        print(f"{hops} hops (expect >1)")

        print("sprint-jump:", end=" ", flush=True)
        await send(ws, type="input", forward=True, sprint=True, tap=["jump"])
        speed = await measure_speed(ws, 1.5)
        # This 1.5s window starts from a dead stop, and the jump itself briefly
        # cuts horizontal control on liftoff — that startup cost can outweigh
        # the sprint boost over such a short average, so this can legitimately
        # read below (or above) steady-state sprint run to run.
        print(f"{speed:.2f} b/s burst (short window incl. jump liftoff; sprint was {sprint:.2f})")

        await send(ws, type="release")
        print("released")


if __name__ == "__main__":
    asyncio.run(main())
