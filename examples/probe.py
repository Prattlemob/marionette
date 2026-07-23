#!/usr/bin/env python3
"""Minimal Marionette bridge probe (protocol v1).

Connects, performs the hello handshake, watches observations for a second,
holds `forward` for three seconds and releases it, then demonstrates the
`configure` message by slowing the observation stream to every 10th tick.

Requires:  pip install websockets
Usage:     python probe.py [port]     (default 24680)
"""
import asyncio
import json
import sys

import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680


async def watch(ws, seconds):
    """Print observations for `seconds`, keeping the socket drained."""
    loop = asyncio.get_running_loop()
    end = loop.time() + seconds
    while (remaining := end - loop.time()) > 0:
        try:
            print(await asyncio.wait_for(ws.recv(), timeout=remaining))
        except TimeoutError:
            break


async def main():
    async with websockets.connect(f"ws://127.0.0.1:{PORT}/") as ws:
        await ws.send(json.dumps({"type": "hello", "versions": [1], "role": "controller"}))
        hello = json.loads(await ws.recv())
        assert hello.get("type") == "hello", f"handshake rejected: {hello}"
        print(f"connected: protocol {hello['version']}, mod {hello['mod']}, "
              f"capabilities {hello['capabilities']}")
        print("-- observing for 1 s --")
        await watch(ws, 1.0)
        print("-- forward ON for 3 s --")
        await ws.send(json.dumps({"type": "input", "forward": True}))
        await watch(ws, 3.0)
        print("-- forward OFF --")
        await ws.send(json.dumps({"type": "input", "forward": False}))
        await watch(ws, 1.0)
        if hello["capabilities"].get("configure"):
            print("-- configure rateDivisor 10: expect ~2 observations/second --")
            await ws.send(json.dumps({"type": "configure", "rateDivisor": 10}))
            await watch(ws, 2.0)
            print("-- configure rateDivisor 1: back to every tick --")
            await ws.send(json.dumps({"type": "configure", "rateDivisor": 1}))
            await watch(ws, 1.0)
        else:
            print("-- server lacks configure capability; skipping demo --")


if __name__ == "__main__":
    asyncio.run(main())
