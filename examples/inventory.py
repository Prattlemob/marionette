#!/usr/bin/env python3
"""M3.4 inventory demo. Requires marionette-mc==0.1.0a1; see examples/README.md for fixture."""
import argparse
import asyncio

from marionette_mc import menu_ref
from _common import connect


async def action(client, op, animated=True, **fields):
    # Synchronization can invalidate a reference; errors are surfaced, never replayed.
    await asyncio.sleep(0.25)
    menu = (await client.inventory("inspect"))["menu"]
    if menu is None:
        raise RuntimeError("No container screen is open")
    result = await client.inventory(op, menu=menu_ref(menu), animated=animated, **fields)
    print(op, fields, "->", result["menu"] and result["menu"]["type"], flush=True)
    return result["menu"]


async def run(port, chest, animated=True):
    async with connect(f"ws://127.0.0.1:{port}/", required_capabilities=["inventory"]) as client:
        if animated:
            client.require("inventoryAnimation")
        await client.release()
        await client.inventory("open")
        try:
            await action(client, "move", animated=animated, source="main.0", destination="hotbar.0")
            await action(client, "swap", animated=animated, source="main.2", hotbar=1)
            await action(client, "equip", animated=animated, source="main.1")
            await action(client, "drop", animated=animated, source="hotbar.0", all=False)
            await action(client, "close")
            if chest:
                # Aim at a closed chest before starting this demo. Inventory actions
                # preserve the camera, so a normal use tap opens it after closing.
                await asyncio.sleep(0.25)
                await client.input(tap=["use"])
                async with asyncio.timeout(10):
                    while True:
                        await asyncio.sleep(0.25)
                        menu = (await client.inventory("inspect"))["menu"]
                        if menu and menu["type"].startswith("minecraft:generic_9x"):
                            break
                target = next(slot["slot"] for slot in menu["slots"]
                              if "alias" not in slot and slot["count"] == 0)
                await action(client, "move", animated=animated, source="hotbar.0", destination=target)
                await action(client, "close")
        finally:
            await client.release()
        print("Done. Results are client predictions; verify the inventory after server synchronization.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=24680)
    parser.add_argument("--chest", action="store_true", help="also open the chest under the crosshair and deposit dirt")
    parser.add_argument("--instant", action="store_true", help="disable visible cursor animation")
    args = parser.parse_args()
    asyncio.run(asyncio.wait_for(run(args.port, args.chest, animated=not args.instant), timeout=120))
