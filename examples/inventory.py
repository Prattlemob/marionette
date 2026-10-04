#!/usr/bin/env python3
"""M3.4/M3.5 inventory demo. Works with marionette-mc==0.1.0a1; see examples/README.md for fixture.

With --chest, deposits into the storage under the crosshair: a vanilla chest, or any
menu the mod reports as generic storage (``support.scope == "storage"``, M3.5),
including modded storage.
"""
import argparse
import asyncio

from marionette_mc import ServerError, menu_ref
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


def storage(menu):
    support = menu.get("support")
    if support is not None:  # inventoryStorage: decided by the mod's storage analysis
        return support["scope"] == "storage"
    return menu["type"].startswith("minecraft:generic_9x")  # older mods: vanilla chests only


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
                        if menu and storage(menu):
                            break
                target = next(slot["slot"] for slot in menu["slots"]
                              if "alias" not in slot and slot["count"] == 0 and "refused" not in slot)
                try:
                    await action(client, "move", animated=animated, source="hotbar.0", destination=target)
                except ServerError as error:
                    # e.g. reason "destination_rejects": a restricted modded slot refused the item.
                    print("move refused:", error.error["code"], error.error.get("reason"), flush=True)
                await action(client, "close")
        finally:
            await client.release()
        print("Done. Results are client predictions; verify the inventory after server synchronization.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=24680)
    parser.add_argument("--chest", action="store_true", help="also open the storage under the crosshair and deposit the hotbar.0 stack")
    parser.add_argument("--instant", action="store_true", help="disable visible cursor animation")
    args = parser.parse_args()
    asyncio.run(asyncio.wait_for(run(args.port, args.chest, animated=not args.instant), timeout=120))
