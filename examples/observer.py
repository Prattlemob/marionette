#!/usr/bin/env python3
"""Marionette read-only observer example (protocol v1, M2.4).

Connects with role "observer", slows its own stream to every 40th tick
(the controller's cadence is unaffected — divisors are per connection),
prints observation frames, and demonstrates that actuation is refused:
sending an input message yields a non-fatal role_forbidden error while
the frames keep flowing. Run it alongside any controller example.

Requires:  pip install websockets
Usage:     python observer.py [port]     (default 24680)
"""
import asyncio
import json
import sys

import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680


async def watch(ws, seconds):
    """Print messages for `seconds`, keeping the socket drained."""
    loop = asyncio.get_running_loop()
    end = loop.time() + seconds
    while (remaining := end - loop.time()) > 0:
        try:
            message = json.loads(await asyncio.wait_for(ws.recv(), timeout=remaining))
        except TimeoutError:
            break
        tag = "ERROR" if message.get("type") == "error" else "frame"
        print(f"[{tag}] {message}")


async def main():
    async with websockets.connect(f"ws://127.0.0.1:{PORT}/") as ws:
        await ws.send(json.dumps({"type": "hello", "versions": [1], "role": "observer"}))
        hello = json.loads(await ws.recv())
        assert hello.get("type") == "hello", f"handshake rejected: {hello}"
        print(f"observing: protocol {hello['version']}, mod {hello['mod']}")
        print("-- slowing my stream to every 40th tick (controller unaffected) --")
        await ws.send(json.dumps({"type": "configure", "rateDivisor": 40}))
        await watch(ws, 4.0)
        print("-- trying to actuate (must be refused with role_forbidden) --")
        await ws.send(json.dumps({"type": "input", "forward": True, "id": 1}))
        await watch(ws, 4.0)
        print("-- still receiving frames; read-only enforcement verified --")


if __name__ == "__main__":
    asyncio.run(main())
