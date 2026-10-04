#!/usr/bin/env python3
"""M3.6 crafting demo (``crafting``): place a layout the agent chose, then craft.

The mod never chooses recipes, ingredients or destinations: this script takes them
from the command line. With no screen open it opens the survival inventory (2x2 grid);
open a crafting table first (aim at it and tap use) for a 3x3 grid. Example, oak
planks from one log in the first grid cell into hotbar slot 0, twice::

    python examples/crafting.py --place 1=main.0:2 --to hotbar.0 --crafts 2

``--place`` takes ``GRID=SOURCE[:COUNT]``: GRID is a 1-based cell of the grid (row by
row, 1-4 or 1-9), SOURCE a player alias or menu slot, COUNT items (default 1).
Needs the in-repository client; the published 0.1.0a1 alpha has no crafting API.
"""
import argparse
import asyncio

from marionette_mc import ServerError, menu_ref
from _common import connect


def source(text):
    return int(text) if text.isdigit() else text


async def fresh(client):
    menu = (await client.inventory("inspect"))["menu"]
    if menu is None or "crafting" not in menu:
        raise RuntimeError("no crafting grid is open")
    return menu


async def request(client, op, **fields):
    """A grid change makes the server re-send its result with a new state id, so each
    request is built from a fresh inspect and a stale_menu is re-inspected, never replayed blindly."""
    for _ in range(5):
        try:
            return await client.inventory(op, menu=menu_ref(await fresh(client)), **fields)
        except ServerError as error:
            if error.code != "stale_menu":
                raise
            await asyncio.sleep(0.1)
    raise RuntimeError("menu kept changing")


async def run(port, placements, destination, crafts, animated):
    async with connect(f"ws://127.0.0.1:{port}/",
                       required_capabilities=["inventory", "crafting"]) as client:
        await client.release()
        if (await client.inventory("inspect"))["menu"] is None:
            await client.inventory("open")
        grid = (await fresh(client))["crafting"]
        for cell, (slot_ref, count) in placements:
            await request(client, "move", source=slot_ref, destination=grid["grid"][cell - 1], count=count)
            print(f"placed {count} from {slot_ref} into grid cell {cell}", flush=True)
        # The result slot shows what the server offers once it has synchronized the grid.
        async with asyncio.timeout(5):
            while True:
                menu = await fresh(client)
                offered = next(s for s in menu["slots"] if s["slot"] == grid["result"])
                if offered["count"]:
                    break
                await asyncio.sleep(0.1)
        print(f"server offers {offered['count']} x {offered['item']}", flush=True)
        try:
            result = await request(client, "craft", destination=destination, count=crafts, animated=animated)
        except ServerError as error:
            # e.g. no_result, missing_ingredients, destination_full; nothing was clicked.
            print("craft refused:", error.code, error.reason, flush=True)
            return
        placed = next(s for s in result["menu"]["slots"] if s.get("alias") == destination or s["slot"] == destination)
        print(f"crafted {crafts}x; {destination} now holds {placed['count']} x {placed['item']} (client prediction)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=24680)
    parser.add_argument("--place", action="append", default=[], metavar="GRID=SOURCE[:COUNT]")
    parser.add_argument("--to", default="hotbar.0", help="destination slot for the crafted items")
    parser.add_argument("--crafts", type=int, default=1, help="number of crafts (each takes the whole result)")
    parser.add_argument("--instant", action="store_true", help="disable visible cursor animation")
    args = parser.parse_args()
    layout = []
    for item in args.place:
        cell, _, ref = item.partition("=")
        ref, _, count = ref.partition(":")
        layout.append((int(cell), (source(ref), int(count or 1))))
    asyncio.run(asyncio.wait_for(run(args.port, layout, source(args.to), args.crafts, not args.instant), timeout=120))
