#!/usr/bin/env python3
"""Minimal Marionette bridge probe (protocol v0).

Connects, performs the hello handshake, watches observations for a second,
then holds `forward` for three seconds and releases it — the M1.2
definition of done, visible in the game window.

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
        await ws.send(json.dumps({"type": "hello", "version": 0}))
        print("hello reply:", await ws.recv())
        print("-- observing for 1 s --")
        await watch(ws, 1.0)
        print("-- forward ON for 3 s --")
        await ws.send(json.dumps({"type": "input", "forward": True}))
        await watch(ws, 3.0)
        print("-- forward OFF --")
        await ws.send(json.dumps({"type": "input", "forward": False}))
        await watch(ws, 1.0)


if __name__ == "__main__":
    asyncio.run(main())
