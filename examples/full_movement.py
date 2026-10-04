#!/usr/bin/env python3
"""Marionette M3.1 demo (protocol v2): the complete movement set.

Runs every held control, measuring horizontal speed from observations
so each mode is self-evident: walk -> sprint (faster) -> sneak (slower),
then strafes and back. Every hold is bounded: forward runs about 9 s
across walk, sprint and sneak, each other leg 1.5 s. With --jumps it also
runs a single tap-jump vs a held bunny-hop and a sprint-jump burst;
without it the player never jumps. Watch the rendered client while it
runs; speeds print as blocks/second. Needs about 35 blocks of open, flat
ground ahead of the player.

The sneak-to-edge safety check stays manual: stand the player near a
drop, run with --sneak-edge, and the player must stop at the lip.

Works with the published client:  pip install "marionette-mc==0.1.0a1"
Usage:     python examples/full_movement.py [port] [--jumps] [--sneak-edge]   (default 24680)
"""
import asyncio
import math
import sys

from _common import connect

args = [a for a in sys.argv[1:] if not a.startswith("--")]
SNEAK_EDGE = "--sneak-edge" in sys.argv
JUMPS = "--jumps" in sys.argv
PORT = int(args[0]) if args else 24680


async def next_observation(ws):
    return await ws.next_observation(timeout=10)


async def sync(ws):
    """The package retains only the latest unread observation."""
    return await ws.next_observation(timeout=10)


async def measure_speed(ws, seconds):
    """Horizontal blocks/second over roughly the next `seconds`."""
    start = await sync(ws)
    while True:
        end = await next_observation(ws)
        if end["tick"] - start["tick"] >= seconds * 20:
            break
    dist = math.dist((end["player"]["x"], end["player"]["z"]), (start["player"]["x"], start["player"]["z"]))
    return dist / ((end["tick"] - start["tick"]) / 20.0)


async def count_hops(ws, seconds):
    """Rising y edges (jump take-offs) over roughly the next `seconds`."""
    first = await sync(ws)
    base, hops, airborne = first["player"]["y"], 0, False
    while True:
        obs = await next_observation(ws)
        if obs["tick"] - first["tick"] >= seconds * 20:
            return hops
        up = obs["player"]["y"] > base + 0.01
        if up and not airborne:
            hops += 1
        airborne = up


async def main():
    async with connect(f"ws://127.0.0.1:{PORT}/", role="controller", sections=["player"], required_capabilities=["tap"]) as ws:
        hello = ws.hello
        assert hello.get("type") == "hello", f"handshake rejected: {hello}"
        assert hello.get("capabilities", {}).get("tap"), "mod lacks tap capability"

        # Pin the heading so every leg is comparable.
        start = await next_observation(ws)
        await ws.look(yaw=start["player"]["yaw"], pitch=0.0)

        if SNEAK_EDGE:
            print("sneak-edge: sneaking forward for 4 s — player must stop at the lip")
            await ws.input(forward=True, sneak=True)
            before = await next_observation(ws)
            await asyncio.sleep(4.0)
            after = await sync(ws)
            await ws.release()
            dropped = before["player"]["y"] - after["player"]["y"]
            print(f"y change: {dropped:.2f} over {after['tick'] - before['tick']} ticks (expect ~0: did not fall)")
            return

        print("walk:", end=" ", flush=True)
        await ws.input(forward=True)
        walk = await measure_speed(ws, 3.0)
        print(f"{walk:.2f} b/s")

        print("sprint:", end=" ", flush=True)
        await ws.input(sprint=True)
        sprint = await measure_speed(ws, 3.0)
        print(f"{sprint:.2f} b/s (expect ~1.3x walk)")

        print("sneak:", end=" ", flush=True)
        await ws.input(sprint=False, sneak=True)
        sneak = await measure_speed(ws, 3.0)
        print(f"{sneak:.2f} b/s (expect ~0.3x walk)")
        await ws.input(forward=False, sneak=False)
        await asyncio.sleep(0.5)  # settle

        for name, fields in (("strafe left", {"left": True}),
                             ("strafe right", {"right": True}),
                             ("back", {"back": True})):
            await ws.input(**fields)
            speed = await measure_speed(ws, 1.5)
            await ws.input(**{key: False for key in fields})
            print(f"{name}: {speed:.2f} b/s")
            await asyncio.sleep(0.5)

        if not JUMPS:
            await ws.release()
            print("released (jump legs skipped; pass --jumps to run them)")
            return

        print("tap jump:", end=" ", flush=True)
        await ws.input(tap=["jump"])
        print(f"{await count_hops(ws, 2.0)} hop(s) (expect exactly 1)")

        print("held jump:", end=" ", flush=True)
        await ws.input(jump=True)
        hops = await count_hops(ws, 2.0)
        await ws.input(jump=False)
        print(f"{hops} hops (expect >1)")

        print("sprint-jump:", end=" ", flush=True)
        await ws.input(forward=True, sprint=True, tap=["jump"])
        speed = await measure_speed(ws, 1.5)
        # This 1.5s window starts from a dead stop, and the jump itself briefly
        # cuts horizontal control on liftoff — that startup cost can outweigh
        # the sprint boost over such a short average, so this can legitimately
        # read below (or above) steady-state sprint run to run.
        print(f"{speed:.2f} b/s burst (short window incl. jump liftoff; sprint was {sprint:.2f})")

        await ws.release()
        print("released")


if __name__ == "__main__":
    asyncio.run(asyncio.wait_for(main(), timeout=120))
