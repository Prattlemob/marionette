#!/usr/bin/env python3
"""Marionette read-only observer example (protocol v2, M2.4).

Connects with role "observer", slows its own stream to every 40th tick
(the controller's cadence is unaffected — divisors are per connection),
prints observation frames, and demonstrates that actuation is refused:
attempting input raises a local RoleError while
the frames keep flowing. Run it alongside any controller example.

Requires:  pip install "marionette-mc==0.1.0a1"
Usage:     python observer.py [port]     (default 24680)
"""
import asyncio
import sys

from marionette_mc import RoleError
from _common import connect

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680


async def watch(ws, seconds):
    """Print messages for `seconds`, keeping the socket drained."""
    loop = asyncio.get_running_loop()
    end = loop.time() + seconds
    while (remaining := end - loop.time()) > 0:
        try:
            message = await ws.next_observation(timeout=remaining)
        except (asyncio.TimeoutError, TimeoutError):
            break
        tag = "ERROR" if message.get("type") == "error" else "frame"
        print(f"[{tag}] {message}")


async def main():
    async with connect(f"ws://127.0.0.1:{PORT}/", role="observer", sections=["player"]) as ws:
        hello = ws.hello
        assert hello.get("type") == "hello", f"handshake rejected: {hello}"
        print(f"observing: protocol {hello['version']}, mod {hello['mod']}")
        print("-- slowing my stream to every 40th tick (controller unaffected) --")
        await ws.configure(rate_divisor=40)
        await watch(ws, 4.0)
        print("-- trying to actuate (must be refused with RoleError) --")
        try:
            await ws.input(forward=True)
        except RoleError as error:
            print("local role guard:", error)
        await watch(ws, 4.0)
        print("-- still receiving frames; read-only enforcement verified --")


if __name__ == "__main__":
    asyncio.run(main())
