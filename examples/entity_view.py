#!/usr/bin/env python3
"""Read-only live nearby-entity list (M4.4, `entityState`).

Needs the in-repository client (pip install -e ./python); the published
0.1.0a1 alpha cannot select the entities section.
Usage: python examples/entity_view.py [port] [--once] [--json]
Lists the entities around the player, nearest first, with their hostility
classification, and flags when the configured count cap truncated the list.
"""
import asyncio
import json
import sys

from _common import connect


def short(registry_id):
    return registry_id.removeprefix("minecraft:")


def describe_entity(entity):
    what = short(entity["type"])
    if "name" in entity:
        what += f" {entity['name']}"
    if "item" in entity:
        what += f" ({short(entity['item']['item'])} x{entity['item']['count']})"
    v = entity["velocity"]
    speed = (v["x"] ** 2 + v["y"] ** 2 + v["z"] ** 2) ** 0.5
    line = (f"{entity['distance']:6.2f}  {entity['hostility']:<8} {what:<28} #{entity['id']:<6} "
            f"at {entity['x']:8.2f} {entity['y']:7.2f} {entity['z']:8.2f}  {speed * 20:5.2f} b/s")
    if "health" in entity:
        line += f"  hp {entity['health']:g}/{entity['maxHealth']:g}  targeting me: {entity['targetingMe']}"
    return line


def describe(section):
    header = (f"{section['total']} within {section['radius']} blocks, "
              f"listing {len(section['nearby'])} (cap {section['maxCount']})")
    if section["truncated"]:
        header += " - TRUNCATED, nearest kept"
    return "\n".join([header] + [describe_entity(e) for e in section["nearby"]])


async def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    port = int(args[0]) if args else 24680
    async with connect(f"ws://127.0.0.1:{port}/", role="observer", sections=["entities"]) as ws:
        async for frame in ws.observations():
            if "entities" not in frame:
                continue
            if "--json" in sys.argv:
                print(json.dumps(frame), flush=True)
            else:
                text = f"Marionette entities | tick {frame['tick']}\n{describe(frame['entities'])}"
                print(("\033[H\033[2J" if sys.stdout.isatty() else "") + text, flush=True)
            if "--once" in sys.argv:
                return


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
