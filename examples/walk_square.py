#!/usr/bin/env python3
"""Marionette walking-skeleton demo (protocol v0): walk a square.

Walks four ~5-block sides with 90° turns and reports how far from the
start the player ended up. Doubles as the disconnect-safety test target:
`kill -9` this script mid-walk and the player must stop within one tick.

Requires:  pip install websockets
Usage:     python walk_square.py [port]     (default 24680)
"""
import asyncio
import json
import math
import sys

import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680
SIDE_BLOCKS = 5.0


async def next_observation(ws):
    while True:
        message = json.loads(await ws.recv())
        if message.get("type") == "observation":
            return message
        print("non-observation message:", message)


async def main():
    async with websockets.connect(f"ws://127.0.0.1:{PORT}/") as ws:
        await ws.send(json.dumps({"type": "hello", "version": 0}))
        hello = json.loads(await ws.recv())
        assert hello.get("type") == "hello", hello

        start = await next_observation(ws)
        yaw = start["yaw"]
        print(f"start ({start['x']:.1f}, {start['z']:.1f}) yaw {yaw:.0f}")

        for side in range(4):
            await ws.send(json.dumps({"type": "look", "yaw": yaw, "pitch": 0.0}))
            origin = await next_observation(ws)
            await ws.send(json.dumps({"type": "input", "forward": True}))
            while True:
                obs = await next_observation(ws)
                walked = math.dist((obs["x"], obs["z"]), (origin["x"], origin["z"]))
                if walked >= SIDE_BLOCKS:
                    break
            await ws.send(json.dumps({"type": "input", "forward": False}))
            print(f"side {side + 1}: walked {walked:.1f} blocks")
            yaw += 90.0

        await asyncio.sleep(0.5)  # let momentum settle
        end = await next_observation(ws)
        error = math.dist((end["x"], end["z"]), (start["x"], start["z"]))
        print(f"finished {error:.1f} blocks from start")
        await ws.send(json.dumps({"type": "release"}))


if __name__ == "__main__":
    asyncio.run(main())
