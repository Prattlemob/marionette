#!/usr/bin/env python3
"""Read-only live player dashboard (protocol 2).

Requires: pip install websockets
Usage: python examples/dashboard.py [port] (default 24680)
Take fall damage, sprint, swim, or gain an effect to watch the state change.
"""
import asyncio
import json
import sys

import websockets


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 24680
    async with websockets.connect(f"ws://127.0.0.1:{port}/") as ws:
        await ws.send(json.dumps({"type": "hello", "versions": [2],
                                  "role": "observer", "sections": ["player"]}))
        hello = json.loads(await ws.recv())
        if hello.get("type") != "hello":
            raise RuntimeError(f"Handshake rejected: {hello}")
        async for raw in ws:
            frame = json.loads(raw)
            if frame.get("type") != "observation":
                print(frame)
                continue
            p = frame.get("player")
            if p is None:
                continue
            flags = ", ".join(k for k in ("onGround", "inWater", "sneaking",
                                          "sprinting", "sleeping", "onFire") if p[k])
            effects = ", ".join(f"{e['id']} {e['amplifier'] + 1} ({e['duration']} ticks)"
                                for e in p["effects"]) or "none"
            v, xp = p["velocity"], p["xp"]
            text = (
                f"Marionette | tick {frame['tick']}\n"
                f"Position {p['x']:.2f}, {p['y']:.2f}, {p['z']:.2f}  "
                f"Yaw {p['yaw']:.1f}  Pitch {p['pitch']:.1f}\n"
                f"Velocity {v['x']:.3f}, {v['y']:.3f}, {v['z']:.3f} blocks/tick\n"
                f"Health {p['health']:g}/{p['maxHealth']:g}  Hunger {p['hunger']}  "
                f"Saturation {p['saturation']:g}  Air {p['air']}/{p['maxAir']}\n"
                f"XP level {xp['level']} + {xp['progress']:.0%} (total {xp['total']})\n"
                f"Flags: {flags or 'none'}\nEffects: {effects}"
            )
            print(("\033[H\033[2J" if sys.stdout.isatty() else "") + text, flush=True)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
