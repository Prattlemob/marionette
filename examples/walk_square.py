#!/usr/bin/env python3
"""Marionette walking-skeleton demo (protocol 2): walk a square.

Walks four ~5-block sides with 90° turns and reports how far from the
start the player ended up. Every forward hold is bounded: a side ends after
SIDE_SECONDS or when the player is blocked, and the script never jumps.
Stand the player on open, flat ground with room for a 5-block square.
Doubles as the disconnect-safety test target: `kill -9` this script mid-walk
and the player must stop within one tick.

Works with the published client:  pip install "marionette-mc==0.1.0a1"
Usage:     python examples/walk_square.py [port]     (default 24680)
"""
import asyncio
import math
import sys

from _common import connect

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680
SIDE_BLOCKS = 5.0
SIDE_SECONDS = 3.0  # walking speed is ~4.3 blocks/s, so 5 blocks take ~1.2 s


async def next_observation(ws):
    return await ws.next_observation(timeout=10)


async def walk_side(ws):
    """Hold forward until SIDE_BLOCKS walked, SIDE_SECONDS passed, or blocked."""
    origin = (await next_observation(ws))["player"]
    loop = asyncio.get_running_loop()
    began = loop.time()
    walked, outcome = 0.0, "time limit"
    await ws.input(forward=True)
    try:
        while loop.time() - began < SIDE_SECONDS:
            p = (await next_observation(ws))["player"]
            walked = math.dist((p["x"], p["z"]), (origin["x"], origin["z"]))
            if walked >= SIDE_BLOCKS:
                outcome = "done"
                break
            if loop.time() - began > 1.0 and walked < 0.2:
                outcome = "blocked"
                break
    finally:
        await ws.input(forward=False)
    return walked, outcome


async def main():
    async with connect(f"ws://127.0.0.1:{PORT}/", role="controller", sections=["player"]) as ws:
        hello = ws.hello
        assert hello.get("type") == "hello", f"handshake rejected: {hello}"

        start = await next_observation(ws)
        yaw = start["player"]["yaw"]
        print(f"start ({start['player']['x']:.1f}, {start['player']['z']:.1f}) yaw {yaw:.0f}")

        try:
            for side in range(4):
                await ws.look(yaw=yaw, pitch=0.0)
                walked, outcome = await walk_side(ws)
                print(f"side {side + 1}: walked {walked:.1f} blocks ({outcome})")
                yaw += 90.0

            await asyncio.sleep(0.5)  # let momentum settle
            end = await next_observation(ws)
            error = math.dist((end["player"]["x"], end["player"]["z"]), (start["player"]["x"], start["player"]["z"]))
            print(f"finished {error:.1f} blocks from start")
        finally:
            await ws.release()


if __name__ == "__main__":
    asyncio.run(asyncio.wait_for(main(), timeout=120))
