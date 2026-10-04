#!/usr/bin/env python3
"""Read-only live crosshair target and world context (M4.3, `targetState`/`worldState`).

Needs the in-repository client (pip install -e ./python); the published
0.1.0a1 alpha cannot select the target or world sections.
Usage: python examples/target_view.py [port] [--once] [--json]
Pan the camera across blocks, entities and sky (or change time and weather)
and compare with the F3 debug screen.
"""
import asyncio
import json
import sys

from _common import connect


def short(registry_id):
    return registry_id.removeprefix("minecraft:")


def describe_target(target):
    reach = target["reach"]
    limits = f"(reach: block {reach['block']:g}, entity {reach['entity']:g})"
    if target["kind"] == "block":
        p = target["pos"]
        return (f"Target: block {short(target['block'])} at {p['x']}, {p['y']}, {p['z']} "
                f"face {target['face']}, {target['distance']:.2f} blocks {limits}")
    if target["kind"] == "entity":
        return (f"Target: entity {short(target['entity'])} #{target['id']}, "
                f"{target['distance']:.2f} blocks {limits}")
    return f"Target: none {limits}"  # unknown future kinds are treated as none


def describe_world(world):
    feet, light = world["feet"], world["light"]
    return "\n".join([
        f"Dimension: {world['dimension']}",
        f"Time: {world['timeOfDay']} (day {world['day']}, dayTime {world['dayTime']})",
        f"Weather: {world['weather']} (rain {world['rainLevel']:.2f}, thunder {world['thunderLevel']:.2f})",
        f"Light at feet {feet['x']}, {feet['y']}, {feet['z']}: {light['combined']} "
        f"({light['sky']} sky, {light['block']} block), effective {light['effective']}",
    ])


async def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    port = int(args[0]) if args else 24680
    async with connect(f"ws://127.0.0.1:{port}/", role="observer", sections=["target", "world"]) as ws:
        async for frame in ws.observations():
            if "target" not in frame or "world" not in frame:
                continue
            if "--json" in sys.argv:
                print(json.dumps(frame), flush=True)
            else:
                text = (f"Marionette target/world | tick {frame['tick']}\n"
                        f"{describe_target(frame['target'])}\n{describe_world(frame['world'])}")
                print(("\033[H\033[2J" if sys.stdout.isatty() else "") + text, flush=True)
            if "--once" in sys.argv:
                return


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
