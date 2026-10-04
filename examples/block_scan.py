#!/usr/bin/env python3
"""One bounded block scan around the player (M4.5, `blockScan`).

Needs the in-repository client (pip install -e ./python); the published
0.1.0a1 alpha has no scan API.
Usage: python examples/block_scan.py [port] [--size X,Y,Z] [--min X,Y,Z] [--json]
Connects as the controller, requests one scan (default 16x8x16 centred on the
feet), and prints each horizontal layer as a character map with a legend, or
every decoded block as JSON. Sends no movement; the session ends afterwards.
"""
import asyncio
import json
import string
import sys

from marionette_mc import ServerError, connect, scan_block, scan_blocks


def option(name, default=None):
    if name in sys.argv:
        return tuple(int(v) for v in sys.argv[sys.argv.index(name) + 1].split(","))
    return default


def layers(result):
    size, low = result["size"], result["min"]
    symbols = {}
    marks = iter(" " + string.ascii_letters + string.digits + string.punctuation)
    for block in result["palette"]:
        symbols[block] = " " if block in ("minecraft:air", "minecraft:cave_air") else (
            "?" if block is None else next(c for c in marks if c not in " ?"))
    lines = []
    for y in range(low["y"] + size["y"] - 1, low["y"] - 1, -1):
        lines.append(f"y={y}  (x {low['x']}..{low['x'] + size['x'] - 1} →, z down)")
        for z in range(low["z"], low["z"] + size["z"]):
            lines.append("  " + "".join(symbols[scan_block(result, x, y, z)]
                                         for x in range(low["x"], low["x"] + size["x"])))
    legend = [f"  {mark!r} {block or 'no data (unloaded)'}" for block, mark in symbols.items()]
    return "\n".join(lines + ["legend:"] + legend)


async def main():
    args = [a for i, a in enumerate(sys.argv[1:], 1)
            if not a.startswith("--") and sys.argv[i - 1] not in ("--size", "--min")]
    port = int(args[0]) if args else 24680
    size = option("--size", (16, 8, 16))
    async with connect(f"ws://127.0.0.1:{port}/", required_capabilities=["blockScan"]) as client:
        try:
            result = await client.scan(size, min=option("--min"))
        except ServerError as error:
            print(f"scan refused: {error}; limits {error.error.get('limits')}")
            return
    if "--json" in sys.argv:
        print(json.dumps([{"x": x, "y": y, "z": z, "block": b} for x, y, z, b in scan_blocks(result)]))
        return
    ticks = result["tick"] - result["startTick"] + 1
    print(f"{result['dimension']} box min {result['min']} size {result['size']}: "
          f"{len(result['palette'])} block kinds, read over {ticks} tick(s)")
    print(layers(result))


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
