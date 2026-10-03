#!/usr/bin/env python3
"""Marionette M3.3 demo (protocol v2): attack, use, and hotbar.

The DoD sequence, visible in the rendered client: select the pickaxe
slot, look down at the block in front, hold attack until it breaks,
select the block slot and one-shot-use to place, then select the food
slot and hold use to eat. Provision the hotbar first (see
examples/README.md): slot 0 iron pickaxe, slot 1 dirt, slot 2 cooked
beef (eat needs missing hunger — spend some by sprint-jumping or take
fall damage, or run /effect give @p minecraft:hunger).

Requires:  pip install websockets
Usage:     python interact.py [port]   (default 24680)
"""
import asyncio
import json
import sys

import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680


async def next_observation(ws):
    while True:
        message = json.loads(await ws.recv())
        if message.get("type") == "observation":
            return message
        print("non-observation message:", message)


async def send(ws, **fields):
    await ws.send(json.dumps(fields))


async def wait_ticks(ws, ticks):
    """Let roughly `ticks` client ticks pass, draining observations."""
    start = await next_observation(ws)
    while (await next_observation(ws))["tick"] - start["tick"] < ticks:
        pass


async def main():
    async with websockets.connect(f"ws://127.0.0.1:{PORT}/") as ws:
        await send(ws, type="hello", versions=[2], role="controller")
        reply = json.loads(await ws.recv())
        assert reply.get("type") == "hello", reply
        if not reply.get("capabilities", {}).get("interact"):
            sys.exit("mod does not advertise the interact capability")
        print("connected:", reply)

        print("aiming at the block in front of the player's feet")
        obs = await next_observation(ws)
        await send(ws, type="look", mode="smooth", yaw=obs["player"]["yaw"], pitch=55.0)
        await wait_ticks(ws, 30)

        print("slot 0 (pickaxe); holding attack to mine to completion")
        await send(ws, type="input", hotbar=0, attack=True)
        await wait_ticks(ws, 80)  # 4 s: plenty for dirt/grass with a pickaxe
        await send(ws, type="input", attack=False)

        print("slot 1 (dirt); one-shot use to place a block")
        await send(ws, type="input", hotbar=1, tap=["use"])
        await wait_ticks(ws, 10)

        print("slot 2 (food); holding use to eat")
        await send(ws, type="input", hotbar=2, use=True)
        await wait_ticks(ws, 45)  # eating takes 32 ticks
        await send(ws, type="input", use=False)

        await send(ws, type="release")
        print("done — released")


if __name__ == "__main__":
    asyncio.run(main())
