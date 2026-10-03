#!/usr/bin/env python3
"""M3.4 inventory demo. Requires websockets; see examples/README.md for fixture."""
import argparse
import asyncio
import json

import websockets


class InventoryClient:
    def __init__(self, ws, animated=True):
        self.ws = ws
        self.animated = animated
        self.sequence = 0

    async def request(self, op, **fields):
        self.sequence += 1
        request_id = f"inventory-{self.sequence}"
        await self.ws.send(json.dumps(dict(type="inventory", id=request_id, op=op, **fields)))
        async with asyncio.timeout(10):
            while True:
                reply = json.loads(await self.ws.recv())
                if reply.get("id") != request_id:
                    continue
                if reply["type"] == "error":
                    raise RuntimeError(f'{reply["code"]}: {reply["message"]}')
                return reply["menu"]

    async def action(self, op, **fields):
        # Allow vanilla synchronization, then obtain the current menu/state id.
        # A stale_menu response remains possible; surface it instead of replaying a drop.
        await asyncio.sleep(0.25)
        menu = await self.request("inspect")
        if menu is None:
            raise RuntimeError("No container screen is open")
        ref = {key: menu[key] for key in ("type", "containerId", "stateId")}
        result = await self.request(op, menu=ref, animated=self.animated, **fields)
        print(op, fields, "->", result and result["type"], flush=True)
        return result


async def run(port, chest, animated=True):
    async with websockets.connect(f"ws://127.0.0.1:{port}/") as ws:
        await ws.send(json.dumps(dict(type="hello", versions=[2], role="controller")))
        hello = json.loads(await ws.recv())
        if not hello.get("capabilities", {}).get("inventory"):
            raise RuntimeError(f"Inventory capability unavailable: {hello}")
        if animated and not hello.get("capabilities", {}).get("inventoryAnimation"):
            raise RuntimeError("Animation unavailable; use --instant for an older mod")
        client = InventoryClient(ws, animated=animated)
        await ws.send(json.dumps(dict(type="release")))
        await client.request("open")
        try:
            await client.action("move", **{"from": "main.0", "to": "hotbar.0"})
            await client.action("swap", **{"from": "main.2", "hotbar": 1})
            await client.action("equip", **{"from": "main.1"})
            await client.action("drop", **{"from": "hotbar.0", "all": False})
            await client.action("close")
            if chest:
                # Aim at a closed chest before starting this demo. Inventory actions
                # preserve the camera, so a normal use tap opens it after closing.
                await asyncio.sleep(0.25)
                await ws.send(json.dumps(dict(type="input", tap=["use"])))
                async with asyncio.timeout(10):
                    while True:
                        await asyncio.sleep(0.25)
                        menu = await client.request("inspect")
                        if menu and menu["type"].startswith("minecraft:generic_9x"):
                            break
                target = next(slot["slot"] for slot in menu["slots"]
                              if "alias" not in slot and slot["count"] == 0)
                await client.action("move", **{"from": "hotbar.0", "to": target})
                await client.action("close")
        finally:
            await ws.send(json.dumps(dict(type="release")))
        print("Done. Results are client predictions; verify the inventory after server synchronization.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=24680)
    parser.add_argument("--chest", action="store_true", help="also open the chest under the crosshair and deposit dirt")
    parser.add_argument("--instant", action="store_true", help="disable visible cursor animation")
    args = parser.parse_args()
    asyncio.run(run(args.port, args.chest, animated=not args.instant))
